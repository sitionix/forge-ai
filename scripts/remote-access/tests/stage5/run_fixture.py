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


def main():
    root=prepare()
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
