import importlib.util
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

BASE = pathlib.Path(__file__).resolve().parents[1]
def module():
    spec=importlib.util.spec_from_file_location('workload_units',BASE/'workload_units.py')
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

STOPPED='LoadState=loaded\nActiveState=inactive\nSubState=dead\nMainPID=0\nControlGroup=\nJob=\n'
ABSENT=STOPPED.replace('loaded','not-found')
S='10000000-0000-4000-8000-000000000001'
E='20000000-0000-4000-8000-000000000001'

class UnitCleanupTest(unittest.TestCase):
    def setUp(self): self.m=module()
    def test_systemd_unavailable_cannot_confirm_cleanup(self):
        with patch.object(self.m,'run',return_value=subprocess.CompletedProcess([],1,'','Failed to connect to bus')):
            with self.assertRaisesRegex(RuntimeError,'cleanup'): self.m.stop_unit('forge-remote-test.service')
    def test_malformed_or_unsuccessful_inspection_never_means_stopped(self):
        for rc,output in [(1,STOPPED),(0,''),(0,'inactive'),(0,STOPPED+'Unknown=x\n'),(0,STOPPED.replace('inactive','active')),(0,STOPPED.replace('Job=','Job=33'))]:
            with self.subTest(rc=rc,output=output),patch.object(self.m,'run',side_effect=[subprocess.CompletedProcess([],0,'',''),subprocess.CompletedProcess([],rc,output,'')]):
                with self.assertRaisesRegex(RuntimeError,'cleanup'):self.m.stop_unit('forge-remote-test.service')
    def test_absent_unit_is_safe_even_after_failed_stop(self):
        with patch.object(self.m,'run',side_effect=[subprocess.CompletedProcess([],1,'',''),subprocess.CompletedProcess([],0,ABSENT,'')]):
            self.m.stop_unit('forge-remote-test.service')
    def test_stopped_loaded_unit_requires_successful_stop(self):
        with patch.object(self.m,'run',side_effect=[subprocess.CompletedProcess([],1,'',''),subprocess.CompletedProcess([],0,STOPPED,'')]):
            with self.assertRaisesRegex(RuntimeError,'cleanup'):self.m.stop_unit('forge-remote-test.service')
    def test_still_populated_cgroup_is_not_clean(self):
        state=STOPPED.replace('ControlGroup=','ControlGroup=/forge-remote-test')
        with patch.object(self.m,'run',return_value=subprocess.CompletedProcess([],0,state,'')),patch.object(self.m,'cgroup_empty',return_value=False):
            with self.assertRaisesRegex(RuntimeError,'cleanup'):self.m.stop_unit('forge-remote-test.service')

class RegistryTest(unittest.TestCase):
    def setUp(self):
        self.m=module();self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=pathlib.Path(self.tmp.name)
        self.registry=self.m.Registry(self.root)
    def test_registration_survives_restart_and_precedes_start(self):
        unit=self.registry.register(S,E)
        self.assertEqual('forge-remote-'+S.replace('-','')+'-'+E.replace('-','')+'.service',unit)
        restored=self.m.Registry(self.root)
        self.assertEqual([(E,unit)],restored.entries(S))
    def test_cleanup_failure_keeps_registry_and_attempts_other_commands(self):
        second='30000000-0000-4000-8000-000000000001'
        a=self.registry.register(S,E);b=self.registry.register(S,second)
        with patch.object(self.m,'stop_unit',side_effect=[TimeoutError(),None]) as stop:
            with self.assertRaisesRegex(RuntimeError,'cleanup'):self.registry.cleanup(S)
            self.assertEqual(2,stop.call_count)
        self.assertEqual([(E,a)],self.registry.entries(S))
    def test_successful_cleanup_only_removes_selected_session(self):
        other='40000000-0000-4000-8000-000000000001'
        self.registry.register(S,E);self.registry.register(other,other)
        with patch.object(self.m,'stop_unit'):self.registry.cleanup(S)
        self.assertEqual([],self.registry.entries(S));self.assertEqual(1,len(self.registry.entries(other)))
    def test_unknown_registry_content_fails_closed(self):
        (self.root/'bad.json').write_text('{}')
        with self.assertRaises((ValueError,RuntimeError)):self.registry.entries()

class CommandContractTest(unittest.TestCase):
    def setUp(self):self.m=module()
    def test_preserves_literal_arguments_and_no_host_shell(self):
        request={'argv':['/bin/echo','a b',"'quoted'",'$(touch /bad)','$HOME'],'cwd':'/workspace','timeoutSeconds':120}
        self.assertEqual(request,self.m.command_request(request))
        argv=self.m.command_argv(S,E,{'rootfs':'/rootfs','workspace':'/workspace','user':'forge-work'},request,pathlib.Path('/registry')/(E+'.allow'))
        self.assertEqual(request['argv'],argv[argv.index('--')+1:])
        self.assertIn('--expand-environment=no',argv)
        self.assertIn('PrivateNetwork=yes',argv)
        self.assertIn('KillMode=control-group',argv)
    def test_rejects_unbounded_or_malformed_header(self):
        valid={'argv':['/bin/true'],'cwd':'/workspace','timeoutSeconds':60}
        for field,value in [('argv',[]),('argv',['x\x00']),('argv','id'),('timeoutSeconds',True),('timeoutSeconds',0),('timeoutSeconds',86401),('cwd','../../host'),('cwd','/workspace\n')]:
            request=dict(valid);request[field]=value
            with self.subTest(field=field,value=value),self.assertRaises(ValueError):self.m.command_request(request)
        with self.assertRaises(ValueError):self.m.command_request(dict(valid,sessionId=S))

class SupervisorGateTest(unittest.TestCase):
    def setUp(self):
        import sys
        sys.path.insert(0,str(BASE))
        self.addCleanup(lambda:sys.path.remove(str(BASE)))
        spec=importlib.util.spec_from_file_location('workload_supervisor',BASE/'workload_supervisor.py')
        self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.clock=Mock(return_value=10.)
        self.root=pathlib.Path(self.tmp.name)
        self.sut=self.m.Supervisor(self.root,self.root,self.clock)
        self.epoch='30000000-0000-4000-8000-000000000001'
        self.request={'argv':['/bin/true'],'cwd':'/workspace','timeoutSeconds':60}
    def test_prepare_uses_only_fixed_rootfs_and_verifies_context(self):
        with patch.object(self.m.subprocess,'run') as provision, \
             patch.object(self.m,'prepared_context',return_value={'rootfs':'/srv/forge-remote/rootfs'}) as inspect:
            self.sut.prepare(S)
        argv=provision.call_args.args[0]
        self.assertEqual(argv[0],'/usr/bin/systemd-run')
        self.assertEqual(argv[-5:],['/usr/libexec/forge-remote/prepare-workspace',
                                    '--session',S,'--rootfs','/srv/forge-remote/rootfs'])
        self.assertIn('--property=RuntimeMaxSec=25s',argv)
        inspect.assert_called_once_with(self.root,S)
    def test_failed_prepare_never_claims_prepared_context(self):
        with patch.object(self.m.subprocess,'run',side_effect=RuntimeError('unavailable')), \
             patch.object(self.m,'prepared_context') as inspect:
            with self.assertRaisesRegex(RuntimeError,'unavailable'):self.sut.prepare(S)
        inspect.assert_not_called()
    def test_unreconciled_or_expired_authority_cannot_start(self):
        ticket=self.sut.attach(S,self.request)
        with self.assertRaisesRegex(RuntimeError,'authority'):self.sut.start(S,ticket,self.epoch,[0,1,2])
        self.sut.reconcile(self.epoch);self.sut.heartbeat(self.epoch)
        self.clock.return_value=30.
        with self.assertRaisesRegex(RuntimeError,'authority'):self.sut.start(S,ticket,self.epoch,[0,1,2])
    def test_ticket_is_bound_one_use_and_registered_before_process_launch(self):
        self.sut.reconcile(self.epoch);self.sut.heartbeat(self.epoch)
        ticket=self.sut.attach(S,self.request)
        def launch(*args,**kwargs):
            self.assertEqual(1,len(self.sut.registry.entries(S)))
            return Mock()
        with patch.object(self.m,'prepared_context',return_value={'user':'work','rootfs':'/root','workspace':'/work'}),patch.object(self.m.subprocess,'Popen',side_effect=launch) as process:
            with self.assertRaises(ValueError):self.sut.start(E,ticket,self.epoch,[0,1,2])
            self.sut.start(S,ticket,self.epoch,[0,1,2])
            with self.assertRaises(ValueError):self.sut.start(S,ticket,self.epoch,[0,1,2])
            self.assertEqual(1,process.call_count)
    def test_stop_invalidates_pending_tickets_and_preserves_other_session(self):
        self.sut.reconcile(self.epoch);self.sut.heartbeat(self.epoch)
        a=self.sut.attach(S,self.request);b=self.sut.attach(E,self.request)
        self.sut.stop(S)
        self.assertNotIn(a,self.sut.tickets);self.assertIn(b,self.sut.tickets)
    def test_cancelled_systemd_wait_success_is_not_a_successful_command(self):
        self.sut.reconcile(self.epoch);self.sut.heartbeat(self.epoch)
        ticket=self.sut.attach(S,self.request)
        process=Mock();process.poll.return_value=None
        with patch.object(self.m,'prepared_context',return_value={'user':'work','rootfs':'/root','workspace':'/work'}),patch.object(self.m.subprocess,'Popen',return_value=process):
            self.sut.start(S,ticket,self.epoch,[0,1,2])
        with patch.object(self.m.Registry,'cleanup'):self.sut.stop(S)
        self.assertEqual(143,self.sut.exit_code(ticket,0))
    def test_new_epoch_invalidates_previous_tickets_and_old_heartbeat(self):
        self.sut.reconcile(self.epoch);self.sut.heartbeat(self.epoch)
        ticket=self.sut.attach(S,self.request)
        self.sut.reconcile(E)
        self.assertNotIn(ticket,self.sut.tickets)
        with self.assertRaises(RuntimeError):self.sut.heartbeat(self.epoch)
    def test_expired_lease_runs_cleanup_and_cannot_be_renewed(self):
        self.sut.reconcile(self.epoch);self.sut.heartbeat(self.epoch)
        self.sut.registry.register(S,E)
        self.clock.return_value=30.
        with patch.object(self.m.Registry,'cleanup') as cleanup:self.sut.tick();cleanup.assert_called_once_with(reap=self.sut.reap_launcher)
        with self.assertRaises(RuntimeError):self.sut.heartbeat(self.epoch)

class ForcedExecutionTest(unittest.TestCase):
    def test_revoke_uses_authenticated_binding_and_accepts_only_truthful_states(self):
        from test_managed_ssh import load
        helper=load('forced_command')
        binding=['session',E,S,'SHA256:'+'A'*43]
        for state in ['REVOKING','REVOKED']:
            with patch.object(helper,'query',return_value=state+'\n') as query:
                self.assertEqual((state+'\n',0),helper.handle(binding,'revoke'))
                self.assertEqual('REVOKE '+E+' '+S+' '+binding[3]+'\n',query.call_args.args[0])
        with patch.object(helper,'query',return_value='ACTIVE\n'):
            self.assertEqual(('DENIED\n',1),helper.handle(binding,'revoke'))
        with patch.object(helper,'query') as query:
            self.assertEqual(('DENIED\n',1),helper.handle(['invitation']+binding[1:],'revoke'))
            query.assert_not_called()

class InstallationTest(unittest.TestCase):
    def test_generated_unit_has_watchdog_protected_control_and_no_agent_root(self):
        from test_managed_ssh import load
        setup=load('install')
        unit=setup.workload_unit().decode()
        self.assertIn('Type=notify',unit);self.assertIn('WatchdogSec=15s',unit)
        self.assertIn('User=root',unit);self.assertIn('KillMode=control-group',unit)
        self.assertIn('/var/lib/forge-remote/executions',unit)
        self.assertNotIn('forge-agent.jar',unit)
    def test_prepared_identity_cannot_have_privileged_supplementary_groups(self):
        m=module()
        import json,types
        with tempfile.TemporaryDirectory() as temp:
            manifest=pathlib.Path(temp)/(S+'.json')
            manifest.write_text(json.dumps({'rootfs':'/srv/forge-remote/root','workspace':'/srv/forge-remote/work','user':'frw-'+'a'*24}));manifest.chmod(0o600)
            account=types.SimpleNamespace(pw_uid=500,pw_shell='/usr/sbin/nologin',pw_dir='/nonexistent')
            with patch('pwd.getpwnam',return_value=account),patch('grp.getgrall',return_value=[types.SimpleNamespace(gr_mem=['frw-'+'a'*24])]):
                with self.assertRaisesRegex(ValueError,'Supplementary'):m.prepared_context(pathlib.Path(temp),S)

class TransportHomeTest(unittest.TestCase):
    def test_managed_transport_has_existing_root_owned_nonwritable_home(self):
        from test_managed_ssh import load
        setup=load('install')
        import types
        with tempfile.TemporaryDirectory() as temp,patch.object(setup,'STATE',pathlib.Path(temp)),patch.object(setup,'directory') as directory,patch.object(setup,'run') as run,patch.object(setup,'check_user') as check:
            account=types.SimpleNamespace(pw_dir='/nonexistent')
            with patch.object(setup.pwd,'getpwnam',return_value=account):setup.prepare_transport_home()
            directory.assert_called_once_with(pathlib.Path(temp)/'transport-home',0o555)
            run.assert_called_once_with('/usr/sbin/usermod','--home',str(pathlib.Path(temp)/'transport-home'),'forge-ssh')
            check.assert_called_once()

class SubmissionFenceTest(unittest.TestCase):
    setUp=SupervisorGateTest.setUp
    def test_stop_cancels_unsubmitted_launcher_before_removing_registration(self):
        self.sut.reconcile(self.epoch)
        ticket=self.sut.attach(S,self.request)
        process=Mock();process.poll.return_value=None
        with patch.object(self.m,'prepared_context',return_value={'user':'work','rootfs':'/root','workspace':'/work'}),patch.object(self.m.subprocess,'Popen',return_value=process):
            self.sut.start(S,ticket,self.epoch,[0,1,2])
        fence=self.root/(ticket+'.allow')
        self.assertTrue(fence.exists())
        def stopped(unit):
            self.assertFalse(fence.exists())
            process.wait.assert_called()
        with patch('workload_units.stop_unit',side_effect=stopped):self.sut.stop(S)
        process.terminate.assert_called_once()
        self.assertEqual([],self.sut.registry.entries(S))
    def test_disconnect_closes_submission_fence_and_reaps_launcher(self):
        self.sut.reconcile(self.epoch)
        ticket=self.sut.attach(S,self.request)
        process=Mock();process.poll.return_value=None
        with patch.object(self.m,'prepared_context',return_value={'user':'work','rootfs':'/root','workspace':'/work'}),patch.object(self.m.subprocess,'Popen',return_value=process):
            self.sut.start(S,ticket,self.epoch,[0,1,2])
        with patch('workload_units.stop_unit'):self.sut.finish(S,ticket)
        process.terminate.assert_called_once();process.wait.assert_called()
        self.assertFalse((self.root/(ticket+'.allow')).exists())
    def test_failed_launcher_reap_retains_registration_and_still_cleans_other_units(self):
        self.sut.reconcile(self.epoch)
        tickets=[]
        for index in range(2):
            ticket=self.sut.attach(S,self.request);tickets.append(ticket)
            process=Mock();process.poll.return_value=None
            if index==0:process.wait.side_effect=subprocess.TimeoutExpired('systemd-run',2)
            with patch.object(self.m,'prepared_context',return_value={'user':'work','rootfs':'/root','workspace':'/work'}),patch.object(self.m.subprocess,'Popen',return_value=process):
                self.sut.start(S,ticket,self.epoch,[0,1,2])
        with patch('workload_units.stop_unit') as stop:
            with self.assertRaisesRegex(RuntimeError,'cleanup'):self.sut.stop(S)
            self.assertEqual(2,stop.call_count)
        self.assertEqual([tickets[0]],[entry[0] for entry in self.sut.registry.entries(S)])
        self.assertFalse(list(self.root.glob('*.allow')))

class WorkerIsolationTest(unittest.TestCase):
    setUp=SupervisorGateTest.setUp
    def test_saturated_attachments_and_admin_do_not_block_heartbeat(self):
        import concurrent.futures,socket,threading
        registry=self.root/'registry';registry.mkdir(mode=0o700)
        self.sut=self.m.Supervisor(registry,self.root,self.clock)
        stopped=threading.Event();release=threading.Event();admin_entered=threading.Event()
        peers=[];listeners=[];count=0;lock=threading.Lock();saturated=threading.Event()
        def blocked_peer(*args):
            nonlocal count
            with lock:
                count+=1
                if count==16:saturated.set()
            release.wait(5)
        def blocked_admin(*args):admin_entered.set();release.wait(5)
        def beat(connection,*args):connection.sendall(b'OK\n')
        for name in ['admin','peer','heartbeat']:
            listener=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
            listener.bind(str(self.root/name));listener.listen(32);listeners.append(listener)
        try:
            with patch.object(self.m,'admin_request',side_effect=blocked_admin),patch.object(self.m,'attached_command',side_effect=blocked_peer),patch.object(self.m,'heartbeat_request',side_effect=beat),patch.object(self.m,'notify'):
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                    task=executor.submit(self.m.serve_connections,self.sut,*listeners,123,456,stopped)
                    try:
                        for _ in range(16):
                            peer=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);peer.connect(str(self.root/'peer'));peers.append(peer)
                        self.assertTrue(saturated.wait(3))
                        admin=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);admin.connect(str(self.root/'admin'));peers.append(admin)
                        self.assertTrue(admin_entered.wait(3),'revoke worker starved by attachments')
                        with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as heartbeat:
                            heartbeat.settimeout(2);heartbeat.connect(str(self.root/'heartbeat'))
                            self.assertEqual(b'OK\n',heartbeat.recv(32))
                    finally:release.set();stopped.set()
                    task.result(timeout=5)
        finally:
            for peer in peers:peer.close()
            for listener in listeners:listener.close()

class SshDisconnectTest(unittest.TestCase):
    def test_closed_output_pipe_cancels_while_supervisor_is_still_connected(self):
        import os,socket,threading
        from test_managed_ssh import load
        channel=load('execution_channel')
        for disconnected in range(2):
            with self.subTest(disconnected=disconnected):
                # Independent real pipes represent sshd's output readers; no stop_unit mock.
                supervisor,helper=socket.socketpair(socket.AF_UNIX,socket.SOCK_SEQPACKET)
                pipes=[os.pipe(),os.pipe()]
                # Bound a broken implementation which waits only for the result.
                deadline=threading.Timer(1,lambda:supervisor.sendall(b'EXIT 0\n'));deadline.start()
                try:
                    os.close(pipes[disconnected][0])
                    with self.assertRaisesRegex(BrokenPipeError,'SSH output disconnected'):
                        channel.wait_result(helper,[pipe[1] for pipe in pipes])
                finally:
                    deadline.cancel();deadline.join()
                    supervisor.close();helper.close()
                    for index,(reader,writer) in enumerate(pipes):
                        if index!=disconnected:os.close(reader)
                        os.close(writer)
    def test_connected_outputs_preserve_actual_supervisor_result(self):
        import os,socket
        from test_managed_ssh import load
        channel=load('execution_channel')
        supervisor,helper=socket.socketpair(socket.AF_UNIX,socket.SOCK_SEQPACKET)
        pipes=[os.pipe(),os.pipe()]
        try:
            supervisor.sendall(b'EXIT 7\n')
            self.assertEqual(b'EXIT 7\n',channel.wait_result(helper,[pipe[1] for pipe in pipes]))
        finally:
            supervisor.close();helper.close()
            for reader,writer in pipes:os.close(reader);os.close(writer)
