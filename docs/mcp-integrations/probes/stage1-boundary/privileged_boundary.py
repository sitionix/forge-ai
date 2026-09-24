"""Disposable Task2a actual helper + systemd + Codex/Git probe; NOT deployed sudo E2E.
Root authorization is required only after reviewing this exact script. No install,
host users/groups/config changes, network listeners, personal HOME, or real secrets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import queue
import shutil
import stat
import subprocess as sp
import threading
import time
import uuid

REPO = Path(__file__).resolve().parents[4]
SOURCE = REPO/'scripts/runtime/forge-runtime-launcher.py'


def check(condition, label):
    if not condition: raise AssertionError(label)
    print('PASS '+label, flush=True)


def run(command, **kwargs):
    return sp.run(command, capture_output=True, text=True, timeout=kwargs.pop('timeout', 60), **kwargs)


def checked(command, **kwargs):
    result = run(command, **kwargs)
    if result.returncode: raise RuntimeError('fixture command failed: '+command[0]+' '+result.stderr[:1000])
    return result.stdout.strip()


def parent_descriptor(path):
    path=Path(path)
    if not path.is_absolute() or '..' in path.parts: raise ValueError('unsafe fixture path')
    descriptor=os.open('/',os.O_RDONLY|os.O_DIRECTORY|os.O_CLOEXEC)
    try:
        for part in path.parent.parts[1:]:
            following=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW|os.O_CLOEXEC,dir_fd=descriptor)
            os.close(descriptor); descriptor=following
        return descriptor
    except BaseException:
        os.close(descriptor); raise


def write(path, text, mode=0o644, uid=0, gid=0):
    parent=parent_descriptor(path)
    try:
        descriptor=os.open(path.name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW|os.O_CLOEXEC,mode,dir_fd=parent)
        with os.fdopen(descriptor,'w') as output:
            if not stat.S_ISREG(os.fstat(output.fileno()).st_mode): raise ValueError('unsafe fixture output')
            output.write(text); output.flush()
            os.fchmod(output.fileno(),mode); os.fchown(output.fileno(),uid,gid)
    finally: os.close(parent)


def copy_pinned_elf(source, destination, expected_hash):
    # Read only pinned regular bytes; never execute/import the package source.
    descriptor=os.open(source,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK|os.O_CLOEXEC)
    with os.fdopen(descriptor,'rb') as executable:
        if not stat.S_ISREG(os.fstat(executable.fileno()).st_mode): raise ValueError('resource is not regular')
        payload=executable.read(16*1024*1024+1)
    if len(payload)>16*1024*1024 or payload[:4]!=b'\x7fELF' or hashlib.sha256(payload).hexdigest()!=expected_hash:
        raise ValueError('unreviewed ELF resource; explicit new review required')
    parent=parent_descriptor(destination)
    try:
        descriptor=os.open(destination.name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW|os.O_CLOEXEC,0o600,dir_fd=parent)
        with os.fdopen(descriptor,'wb') as output:
            output.write(payload); output.flush()
            os.fchown(output.fileno(),os.geteuid(),os.getegid()); os.fchmod(output.fileno(),0o755)
    finally: os.close(parent)


def read_output(path):
    parent=parent_descriptor(path)
    try:
        descriptor=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK|os.O_CLOEXEC,dir_fd=parent)
        with os.fdopen(descriptor,'rb') as source:
            info=os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1: raise ValueError('unsafe runtime result')
            payload=source.read(8193)
            if len(payload)>8192: raise ValueError('runtime result too large')
            return payload.decode('utf-8')
    finally: os.close(parent)


def state(unit):
    result = run(['/usr/bin/systemctl','show',unit,'--property=LoadState','--property=ActiveState',
                  '--property=MainPID','--property=ControlGroup'])
    values = dict(line.split('=',1) for line in result.stdout.splitlines())
    if result.returncode or set(values) != {'LoadState','ActiveState','MainPID','ControlGroup'}:
        raise RuntimeError('systemd cleanup inspection unavailable')
    return values


def empty(unit):
    current = state(unit)
    if current['MainPID'] != '0' or current['ActiveState'] not in ['inactive','failed']: return False
    group = current['ControlGroup']
    if group:
        events = Path('/sys/fs/cgroup'+group)/'cgroup.events'
        if events.exists() and 'populated 0' not in events.read_text(): return False
    return True


def cleanup(units, base, processes):
    failures=[]
    installation=base.name.removeprefix('forge-stage1-')
    receipt_root=base/'receipts'/installation
    if receipt_root.exists():
        for receipt in receipt_root.glob('*.json'):
            execution=str(uuid.UUID(receipt.stem))
            if execution!=receipt.stem: raise RuntimeError('invalid owned receipt identity')
            unit='forge-runtime-'+installation+'-'+execution+'.service'
            if unit not in units: units.append(unit)
    for unit in reversed(units):
        try:
            result=run(['/usr/bin/systemctl','stop',unit])
            current=state(unit)
            if (result.returncode and current['LoadState'] != 'not-found') or not empty(unit): failures.append(unit)
        except Exception: failures.append(unit)
    for process in processes:
        try:
            if process.poll() is None: process.terminate()
            process.wait(timeout=10)
        except Exception: failures.append('owned pipe process')
    if failures:
        raise RuntimeError('CLEANUP_UNCONFIRMED: retained '+str(base)+' '+','.join(failures))
    shutil.rmtree(base)
    print('CLEANUP_PASS: only recorded fixture UUID units and owned root removed',flush=True)


class Rpc:
    def __init__(self, process):
        self.process=process; self.messages=queue.Queue(); self.sequence=0
        def read():
            for line in process.stdout:
                self.messages.put(json.loads(line))
        self.reader=threading.Thread(target=read,daemon=True); self.reader.start()
    def send(self, message):
        self.process.stdin.write(json.dumps(message)+'\n'); self.process.stdin.flush()
    def call(self, method, params):
        self.sequence+=1
        self.send({'id':self.sequence,'method':method,'params':params})
        deadline=time.monotonic()+35
        while time.monotonic()<deadline:
            message=self.messages.get(timeout=max(.1,deadline-time.monotonic()))
            if message.get('id') == self.sequence:
                if 'error' in message: raise RuntimeError('Codex fixture RPC rejected '+method)
                return message['result']
        raise RuntimeError('Codex fixture RPC timeout')


def require_unused_runtime(uid, process_root=Path('/proc')):
    # Account is fixture-only, never provisioned or modified; don't share a live UID.
    for entry in process_root.glob('[0-9]*'):
        try:
            if entry.stat().st_uid==uid: raise RuntimeError('fixture runtime UID already in use')
        except FileNotFoundError: pass


def main(binary):
    if os.geteuid()!=0: raise RuntimeError('NOT_VERIFIED: interactive root authorization required')
    control_uid=int(os.environ.get('SUDO_UID','0'))
    if control_uid<=0: raise RuntimeError('invoke through sudo from the control test account')
    control_account=pwd.getpwuid(control_uid); runtime=pwd.getpwnam('backup')
    if runtime.pw_uid==control_uid: raise RuntimeError('distinct fixture identity unavailable')
    # Existing backup identity only; its home/files are never read or used.
    require_unused_runtime(runtime.pw_uid)
    installation=str(uuid.uuid4()); base=Path('/run')/('forge-stage1-'+installation)
    base.mkdir(mode=0o755)
    units=[]; processes=[]
    try:
        home=base/'runtime-home'; home.mkdir(mode=0o700)
        codex_home=home/'.codex'; codex_home.mkdir(mode=0o700)
        os.chown(codex_home,runtime.pw_uid,runtime.pw_gid)
        os.chown(home,runtime.pw_uid,runtime.pw_gid)
        workspaces=base/'workspaces'; workspaces.mkdir(mode=0o755)
        work=workspaces/'checkout'; work.mkdir(mode=0o2770); os.chown(work,control_uid,runtime.pw_gid); work.chmod(0o2770)
        protected=base/'control'; protected.mkdir(mode=0o700); os.chown(protected,control_uid,control_account.pw_gid)
        secrets=[]
        for name in ['key','db','operator','service']:
            path=protected/name; write(path,'synthetic-'+name+'-only',0o600,control_uid,control_account.pw_gid); secrets.append(str(path))
        codex=base/'codex'; shutil.copyfile(binary,codex); codex.chmod(0o755)
        digest=hashlib.sha256(codex.read_bytes()).hexdigest()
        if digest!='0b2e9301d6100dddda3b9d5c80ebaeaa3a2f1962388f2f36f6b96a9f08b1f33f':
            raise RuntimeError('unreviewed Codex binary; explicit new review required')
        with codex.open('rb') as executable:
            if executable.read(4)!=b'\x7fELF': raise RuntimeError('Codex is not reviewed ELF')
        resources=base/'codex-resources'; resources.mkdir(mode=0o755); resources.chmod(0o755)
        os.chown(resources,0,0)
        bwrap_hash='77360cb751ccedc5971391444ac86a8a33c15b04d6b4a6fe45f5d25496e62c4c'
        copy_pinned_elf(Path(binary).parent.parent/'codex-resources'/'bwrap',resources/'bwrap',bwrap_hash)
        if (resources/'bwrap').stat().st_uid!=0 or (resources/'bwrap').stat().st_gid!=0:
            raise RuntimeError('resource must be root owned')
        print('BWRAP_SHA256='+bwrap_hash,flush=True)
        print('CODEX_SHA256='+digest,flush=True)
        print('SYSTEMD_VERSION='+checked(['/usr/bin/systemd-run','--version']).splitlines()[0],flush=True)
        version=checked([str(codex),'--version'], user=runtime.pw_uid,group=runtime.pw_gid,extra_groups=[],
                        cwd=work, env={'HOME':str(home),'CODEX_HOME':str(home/'.codex'),'PATH':'/usr/bin:/bin'})
        print('COPIED_CODEX_VERSION='+version,flush=True)
        anchor='forge-stage1-'+installation+'-agent.service'; units.append(anchor)
        checked(['/usr/bin/systemd-run','--quiet','--unit='+anchor,'--property=RuntimeMaxSec=600',
                 '--property=User='+control_account.pw_name,'--property=KillMode=control-group','/usr/bin/sleep','600'])
        backend=sp.Popen(['/usr/bin/python3','-I','-c','import os,time; f=open("db"); time.sleep(600)'],
                         user=control_uid,group=control_account.pw_gid,extra_groups=[runtime.pw_gid], cwd=protected,
                         env={'PATH':'/usr/bin:/bin','FORGE_SYNTHETIC_DB_CANARY':'synthetic-parent-only'},
                         stdin=sp.DEVNULL,stdout=sp.DEVNULL,stderr=sp.DEVNULL)
        processes.append(backend)
        config={'installation':installation,'control_uid':control_uid,'runtime_uid':runtime.pw_uid,
                'runtime_gid':runtime.pw_gid,'runtime_home':str(home),'workspace_roots':[str(workspaces)],
                'agent_unit':anchor,'max_lifetime_seconds':7200,'codex_binary':str(codex),
                'git_binary':'/usr/bin/git','env_binary':str(Path('/usr/bin/env').resolve(strict=True))}
        config_path=base/'config.json'; write(config_path,json.dumps(config),0o600)
        helper=base/'launcher'
        source=SOURCE.read_text()
        for original in ["CONFIG = Path('/etc/forge/runtime-launcher.json')","RECEIPTS = Path('/run/forge-runtime')"]:
            if source.count(original)!=1: raise RuntimeError('reviewed helper substitution mismatch')
        source=source.replace("CONFIG = Path('/etc/forge/runtime-launcher.json')",'CONFIG = Path('+repr(str(config_path))+')')
        source=source.replace("RECEIPTS = Path('/run/forge-runtime')",'RECEIPTS = Path('+repr(str(base/'receipts'))+')')
        write(helper,source,0o755)
        env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','SUDO_UID':str(control_uid),
             'FORGE_SYNTHETIC_DB_CANARY':'synthetic-parent-only','PYTHONPATH':str(work)}
        def invoke(*args, payload=None):
            return run([str(helper),*args],input=payload,env=env)
        def launch(kind,args):
            execution=str(uuid.uuid4()); unit='forge-runtime-'+installation+'-'+execution+'.service'; units.append(unit)
            error=(base/(execution+'.stderr')).open('w')
            process=sp.Popen([str(helper),'start',kind,execution,*args],stdin=sp.PIPE,stdout=sp.PIPE,
                             stderr=error,env=env,text=True)
            error.close(); processes.append(process)
            return execution,unit,process
        shell=work/'shell-check.py'
        write(shell,"import os,pathlib,json\nr={'uid':os.getuid(),'env_clean':'FORGE_SYNTHETIC_DB_CANARY' not in os.environ}\n"+
              "r['secrets_denied']=all(not os.access(p,os.R_OK) and not os.access(p,os.W_OK) for p in "+repr(secrets)+")\n"+
              "pathlib.Path('allowed-write').write_text('synthetic')\nprint(json.dumps(r))\n",0o644,control_uid,runtime.pw_gid)
        hook=work/'fsmonitor.py'
        hook_text="""#!/usr/bin/python3 -I
import json,os,pathlib,time
work=pathlib.Path(WORK)
r={'uid':os.getuid(),'env_clean':'FORGE_SYNTHETIC_DB_CANARY' not in os.environ,
   'secrets_denied':all(not os.access(p,os.R_OK) and not os.access(p,os.W_OK) for p in SECRETS)}
pid=os.fork()
if pid==0:
 os.setsid()
 second=os.fork()
 if second: os._exit(0)
 for fd in [0,1,2]:
  null=os.open('/dev/null',os.O_RDWR); os.dup2(null,fd); os.close(null)
 (work/'descendant').write_text(str(os.getpid()))
 time.sleep(120); os._exit(0)
os.waitpid(pid,0)
for _ in range(100):
 if (work/'descendant').exists(): break
 time.sleep(.02)
(work/'git-report.json').write_text(json.dumps(r))
os.write(1,b'token\\0')
""".replace('WORK',repr(str(work))).replace('SECRETS',repr(secrets))
        write(hook,hook_text,0o755,control_uid,runtime.pw_gid)
        result=invoke('probe',payload=json.dumps({'protected_paths':secrets,'control_pid':backend.pid,'reconcile':True}))
        check(result.returncode==0 and json.loads(result.stdout)['runtimeUid']==runtime.pw_uid,
              'actual new helper probe: separate UID, four protected files, proc aliases, env, cleanup')
        check(invoke('stop','../foreign').returncode!=0,'malformed UUID denied')
        wrong=run([str(helper),'stop',str(uuid.uuid4())],env=dict(env,SUDO_UID=str(runtime.pw_uid)))
        check(wrong.returncode!=0,'wrong claimed caller denied')
        denied=run([str(helper),'probe'],input='{}',env=env,user=runtime.pw_uid,group=runtime.pw_gid,extra_groups=[])
        check(denied.returncode!=0,'runtime cannot invoke root helper')
        (work/'outside').symlink_to(protected,target_is_directory=True)
        result=invoke('start','codex',str(uuid.uuid4()),str(work/'outside'))
        check(result.returncode!=0,'repository symlink cwd rejected')
        execution,unit,process=launch('codex',[str(work)])
        rpc=Rpc(process)
        rpc.call('initialize',{'clientInfo':{'name':'forge_boundary_fixture','version':'1'},'capabilities':{'experimentalApi':True}})
        rpc.send({'method':'initialized','params':{}})
        check(True,'actual Codex initialize stdio handshake')
        result=rpc.call('command/exec',{'command':['/usr/bin/python3',str(shell)],'cwd':str(work),
                       'sandboxPolicy':{'type':'workspaceWrite','writableRoots':[str(work)],'networkAccess':False},'timeoutMs':10000})
        report=json.loads(result['stdout'])
        check(result['exitCode']==0 and report=={'uid':runtime.pw_uid,'env_clean':True,'secrets_denied':True},
              'actual Codex workspaceWrite command keeps network=false and cannot read synthetic control state')
        check(read_output(work/'allowed-write')=='synthetic','runtime writes permitted checkout contents')
        check(invoke('stop',execution).returncode==0 and empty(unit),'Codex owned stop empties actual cgroup')
        process.wait(timeout=15)
        # Real Git hook executes repository content, forks+setsid, and must lose all descendants on success.
        for args in [['-C',str(work),'init'],['-C',str(work),'config','core.fsmonitor',str(hook)]]:
            e,u,p=launch('git',args); output,error=p.communicate(timeout=30)
            check(p.returncode==0 and empty(u),'actual runtime Git setup '+args[-2])
        e,u,p=launch('git',['-C',str(work),'status','--porcelain']); p.communicate(timeout=30)
        check(p.returncode==0 and empty(u),'successful Git command cleanup confirms cgroup empty')
        report=json.loads(read_output(work/'git-report.json'))
        check(report=={'uid':runtime.pw_uid,'env_clean':True,'secrets_denied':True},'hostile fsmonitor uses runtime UID with clean env')
        descendant=int(read_output(work/'descendant'))
        check(not Path('/proc',str(descendant)).exists(),'double-fork setsid hook descendant is gone')
        # Two actual units, blocked stdin, scoped stop, then abrupt Agent lifecycle failure.
        a,ua,pa=launch('git',['-c','alias.fixture=!sleep 120','fixture'])
        b,ub,pb=launch('git',['-c','alias.fixture=!sleep 120','fixture'])
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            if state(ua)['MainPID']!='0' and state(ub)['MainPID']!='0': break
            time.sleep(.05)
        writer=threading.Thread(target=lambda: _blocked_write(pa),daemon=True); writer.start()
        check(invoke('stop',a).returncode==0 and empty(ua),'blocked stdin timeout/recovery owned stop clears A')
        check(state(ub)['MainPID']!='0' and pb.poll() is None,'sibling B survives A cleanup')
        writer.join(timeout=10); check(not writer.is_alive(),'blocked stdin writer released after owned stop')
        checked(['/usr/bin/systemctl','kill','--kill-whom=main','--signal=SIGKILL',anchor])
        deadline=time.monotonic()+15
        while time.monotonic()<deadline and not empty(ub): time.sleep(.1)
        check(empty(ub),'Agent SIGKILL stops bound runtime B')
        print('TASK2A_BOUNDARY_PASS: actual helper/systemd/UID/Codex/Git; installed sudo routing and provider login NOT_VERIFIED',flush=True)
    finally:
        cleanup(units,base,processes)


def _blocked_write(process):
    try: process.stdin.write('x'*1024*1024); process.stdin.flush()
    except (BrokenPipeError,ValueError): pass


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--codex-binary',type=Path,required=True)
    parser.add_argument('--describe',action='store_true')
    args=parser.parse_args()
    if args.describe:
        print('Review only: random /run fixture; transient systemd anchor/units; backup runtime; synthetic files; copied Codex; no host installation.')
    else:
        main(args.codex_binary)
