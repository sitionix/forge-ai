"""Isolated-container root driver; not installed as a production control operation."""
import os,pathlib,subprocess,sys,uuid,time
PACKAGE=pathlib.Path('/opt/forge-remote-package')

def run(*args):subprocess.run(args,check=True,timeout=90)

def prepare():
    run('python3',str(PACKAGE/'install.py'),'--listen-address','127.0.0.1','--port','22222')
    root=pathlib.Path('/srv/forge-remote/rootfs');root.mkdir(parents=True)
    run('cp','-a','/usr',str(root/'usr'))
    for name in ['bin','sbin','lib','lib64']:
        if pathlib.Path('/'+name).exists():(root/name).symlink_to('usr/'+name)
    for name in ['etc','run','proc','dev','tmp','workspace']:(root/name).mkdir()
    for name in ['passwd','group','nsswitch.conf','ld.so.cache']:
        if pathlib.Path('/etc',name).exists():run('cp','/etc/'+name,str(root/'etc'/name))
    state=pathlib.Path('/fixture/state');state.mkdir(parents=True)
    run('chown','-R','forge-control:forge-control',str(state))
    run('systemctl','start','forge-remote-invitations.service','forge-remote-workloads.service','forge-remote-sshd.service')
    return root


def late_submission(session):
    import tempfile
    sys.path.insert(0,str(PACKAGE))
    from workload_units import Registry,prepared_context,command_argv
    execution=str(uuid.uuid4())
    context=prepared_context(pathlib.Path('/etc/forge-remote/workspaces'),session)
    marker=pathlib.Path(context['workspace'])/'late-submission'
    with tempfile.TemporaryDirectory() as directory:
        registry=Registry(pathlib.Path(directory));registry.register(session,execution)
        argv=command_argv(session,execution,context,
            {'argv':['/usr/bin/touch','/workspace/late-submission'],'cwd':'/workspace','timeoutSeconds':5},registry.fence(execution))
        registry.cleanup(session)
        # Deliberately submit after cleanup, as a delayed D-Bus launcher could.
        run(*argv)
        if marker.exists():raise RuntimeError('Late submission executed after cleanup')
    print('PASS actual PID 1 rejects workload submitted after cleanup fence removal',flush=True)


def wait_supervisor():
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        state=subprocess.run(['systemctl','is-active','--quiet','forge-remote-workloads.service'])
        if state.returncode==0 and not list(pathlib.Path('/var/lib/forge-remote/executions').iterdir()):return
        time.sleep(.1)
    raise RuntimeError('Supervisor recovery did not complete')


def cancellation_unit_state(unit):
    result=subprocess.run(['systemctl','show',unit,'--property=LoadState','--property=ActiveState',
                           '--property=SubState','--property=MainPID','--property=Job'],
                          capture_output=True,text=True,check=True,timeout=3)
    pairs=[line.split('=',1) for line in result.stdout.splitlines()]
    state=dict(pairs)
    if len(state)!=len(pairs) or set(state)!={'LoadState','ActiveState','SubState','MainPID','Job'}:
        raise RuntimeError('Cancellation unit inspection malformed')
    return state


def capture_cancellation(session,main_pid,child_pid):
    # Read-only observation: never use STOP/revoke as a substitute for SSH close.
    from workload_units import Registry,canonical
    canonical(session)
    if not main_pid.isdecimal() or not child_pid.isdecimal() or min(int(main_pid),int(child_pid))<=1:
        raise ValueError('Invalid cancellation fixture PID')
    registry=Registry(pathlib.Path('/var/lib/forge-remote/executions'))
    entries=registry.entries(session)
    if len(entries)!=1:raise RuntimeError('Expected exactly one cancellation workload')
    execution,unit=entries[0]
    state=cancellation_unit_state(unit)
    if state['LoadState']!='loaded' or state['ActiveState']!='active' or state['MainPID']!=main_pid:
        raise RuntimeError('Cancellation workload was not running before close')
    if not pathlib.Path('/proc',child_pid).exists():raise RuntimeError('Cancellation child was not running')
    fence=registry.fence(execution)
    if not fence.is_file():raise RuntimeError('Cancellation fence missing before close')
    return unit,registry.root/(execution+'.json'),fence,main_pid,child_pid


def verify_cancellation(captured):
    if captured is None:raise RuntimeError('Cancellation target not captured')
    unit,record,fence,main_pid,child_pid=captured
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        state=cancellation_unit_state(unit)
        stopped=(state['LoadState'] in ('loaded','not-found') and state['ActiveState']=='inactive'
                 and state['SubState']=='dead' and state['MainPID']=='0' and state['Job']=='')
        if (stopped and not pathlib.Path('/proc',main_pid).exists()
                and not pathlib.Path('/proc',child_pid).exists() and not record.exists() and not fence.exists()):return
        time.sleep(.1)
    processes={pid:(pathlib.Path('/proc',pid,'status').read_text().splitlines()[:7]
                    if pathlib.Path('/proc',pid,'status').exists() else 'gone') for pid in (main_pid,child_pid)}
    raise RuntimeError(f'SSH cancellation cleanup unconfirmed: unit={state}, processes={processes}, '
                       f'record={record.exists()}, fence={fence.exists()}')


def main():
    root=prepare()
    cancellation=None
    command=['runuser','-u','forge-control','--','java','-cp',sys.argv[1],
             'com.sitionix.forgeagent.it.tests.RemoteAccessLiveExecutionFixture',*sys.argv[2:]]
    with subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1) as process:
        for line in process.stdout:
            print(line,end='',flush=True)
            if line.startswith('PREPARE '):
                for value in line.strip().split()[1:]:
                    if str(uuid.UUID(value))!=value:raise ValueError('Invalid fixture session')
                    run('python3',str(PACKAGE/'prepare_workspace.py'),'--session',value,'--rootfs',str(root))
                late_submission(line.strip().split()[1])
                process.stdin.write('OK\n');process.stdin.flush()
            elif line.startswith('CAPTURE_CANCELLATION '):
                fields=line.strip().split()
                if len(fields)!=4:raise ValueError('Invalid cancellation capture request')
                cancellation=capture_cancellation(*fields[1:])
                process.stdin.write('OK\n');process.stdin.flush()
            elif line.strip()=='VERIFY_CANCELLATION':
                verify_cancellation(cancellation)
                process.stdin.write('OK\n');process.stdin.flush()
            elif line.strip()=='CRASH_SUPERVISOR':
                run('systemctl','kill','--kill-whom=main','--signal=SIGKILL','forge-remote-workloads.service')
                process.stdin.write('OK\n');process.stdin.flush()
            elif line.strip()=='WAIT_SUPERVISOR':
                wait_supervisor();process.stdin.write('OK\n');process.stdin.flush()
            elif line.strip()=='HANG_SUPERVISOR':
                run('systemctl','kill','--kill-whom=main','--signal=SIGSTOP','forge-remote-workloads.service')
                process.stdin.write('OK\n');process.stdin.flush()
        if process.wait(timeout=30)!=0:raise RuntimeError('Java execution fixture failed')
    # Every registered unit must have been positively cleaned; no rootfs deletion as a substitute.
    registry=pathlib.Path('/var/lib/forge-remote/executions')
    deadline=time.monotonic()+20
    while list(registry.iterdir()) and time.monotonic()<deadline:time.sleep(.1)
    if list(registry.iterdir()):raise RuntimeError('Unconfirmed execution registry remains')
    run('systemctl','is-active','--quiet','forge-remote-workloads.service')
    print('STAGE5_FIXTURE_PASS: real SSH, PostgreSQL, production authority and systemd cleanup',flush=True)

if __name__=='__main__':main()
