"""Fixed systemd workload properties and crash-durable cleanup registration."""
import json
import os
import pathlib
import re
import stat
import subprocess
import uuid


def canonical(value):
    if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise ValueError('Invalid identity')
    return value


def run(*args):
    return subprocess.run(args,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=12)


def protected_file(path,limit=16384):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode & 0o077 or info.st_size>limit:
            raise ValueError('Unsafe workload metadata')
        return os.read(fd,limit+1)
    finally:os.close(fd)


def cgroup_empty(group):
    path=pathlib.PurePosixPath(group)
    if not path.is_absolute() or '..' in path.parts:raise ValueError('Invalid cgroup')
    try:lines=(pathlib.Path('/sys/fs/cgroup')/str(path).lstrip('/')/'cgroup.events').read_text().splitlines()
    except FileNotFoundError:return True
    values=dict(line.split() for line in lines)
    if values.get('populated') not in ('0','1'):raise ValueError('Unknown cgroup state')
    return values['populated']=='0'


def stop_unit(unit):
    if not re.fullmatch(r'forge-remote-[a-z0-9-]+\.service',unit):raise ValueError('Foreign unit')
    try:
        stopped=run('/usr/bin/systemctl','stop',unit)
        inspected=run('/usr/bin/systemctl','show',unit,'--property=LoadState','--property=ActiveState',
                      '--property=SubState','--property=MainPID','--property=ControlGroup','--property=Job')
        if inspected.returncode!=0:raise ValueError('Inspection failed')
        pairs=[line.split('=',1) for line in inspected.stdout.splitlines()]
        fields=dict(pairs)
        if len(fields)!=len(pairs) or set(fields)!={'LoadState','ActiveState','SubState','MainPID','ControlGroup','Job'}:
            raise ValueError('Malformed inspection')
        if fields['LoadState'] not in ('loaded','not-found') or fields['ActiveState']!='inactive' or fields['SubState']!='dead' or fields['MainPID']!='0' or fields['Job']!='':
            raise ValueError('Unit not stopped')
        if stopped.returncode!=0 and fields['LoadState']!='not-found':raise ValueError('Stop failed')
        if fields['ControlGroup'] and not cgroup_empty(fields['ControlGroup']):raise ValueError('Remaining descendants')
    except (OSError,ValueError,subprocess.SubprocessError) as failure:
        raise RuntimeError('Managed workload cleanup unconfirmed') from failure


class Registry:
    def __init__(self,root):
        self.root=pathlib.Path(root)
        info=self.root.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode & 0o077:
            raise ValueError('Unsafe workload registry')

    def register(self,session,execution):
        canonical(session);canonical(execution)
        unit=unit_name(session,execution)
        if len(self.entries())>=256 or len(self.entries(session))>=32:raise RuntimeError('Workload capacity exceeded')
        path=self.root/(execution+'.json')
        descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        try:
            data=json.dumps({'session':session,'execution':execution,'unit':unit}).encode('ascii')
            with os.fdopen(descriptor,'wb',closefd=False) as output:output.write(data);output.flush();os.fsync(descriptor)
        finally:os.close(descriptor)
        self.sync()
        # PID 1 checks this root-owned fence before executing even a late submission.
        fence=os.open(self.fence(execution),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        try:os.fsync(fence)
        finally:os.close(fence)
        self.sync()
        return unit

    def entries(self,session=None):
        result=[]
        for path in sorted(self.root.iterdir()):
            if path.suffix=='.allow':
                canonical(path.stem)
                protected_file(path)
                if not (self.root/(path.stem+'.json')).exists():raise ValueError('Orphan submission fence')
                continue
            if path.suffix!='.json':raise ValueError('Unknown workload artifact')
            record=json.loads(protected_file(path))
            if set(record)!={'session','execution','unit'}:raise ValueError('Invalid workload record')
            sid=canonical(record['session']);eid=canonical(record['execution'])
            if path.name!=eid+'.json' or record['unit']!=unit_name(sid,eid):raise ValueError('Invalid workload record')
            if session is None or sid==session:result.append((eid,record['unit']))
        return result

    def fence(self,execution):return self.root/(canonical(execution)+'.allow')

    def cleanup(self,session=None,reap=None,execution_id=None):
        failures=[]
        entries=[entry for entry in self.entries(session) if execution_id is None or entry[0]==execution_id]
        # Close every submission fence before slow process/systemd waits.
        for execution,unit in entries:
            self.fence(execution).unlink(missing_ok=True)
        self.sync()
        for execution,unit in entries:
            confirmed=True
            if reap is not None:
                try:reap(execution)
                except (OSError,ValueError,RuntimeError,subprocess.SubprocessError):confirmed=False
            try:
                stop_unit(unit)
                if not confirmed:raise RuntimeError('Launcher cleanup unconfirmed')
                (self.root/(execution+'.json')).unlink()
                self.sync()
            except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as failure:failures.append(type(failure).__name__)
        if failures:raise RuntimeError('Managed workload cleanup unconfirmed')

    def sync(self):
        fd=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)


def unit_name(session,execution):return 'forge-remote-'+canonical(session).replace('-','')+'-'+canonical(execution).replace('-','')+'.service'


def command_request(value):
    if not isinstance(value,dict) or set(value)!={'argv','cwd','timeoutSeconds'}:raise ValueError('Invalid command header')
    argv=value['argv'];cwd=value['cwd'];timeout=value['timeoutSeconds']
    if not isinstance(argv,list) or not 1<=len(argv)<=128 or any(not isinstance(arg,str) or '\x00' in arg or len(arg)>4096 for arg in argv) or not argv[0].startswith('/'):
        raise ValueError('Invalid command arguments')
    if sum(len(arg.encode('utf8')) for arg in argv)>8192:raise ValueError('Command limit exceeded')
    if not isinstance(cwd,str) or not cwd.startswith('/') or len(cwd)>1024 or any(ord(c)<32 for c in cwd) or '..' in pathlib.PurePosixPath(cwd).parts:
        raise ValueError('Invalid workload directory')
    if type(timeout)!=int or not 1<=timeout<=86400:raise ValueError('Invalid execution timeout')
    return value


def prepared_context(directory,session):
    root=pathlib.Path(directory)
    data=json.loads(protected_file(root/(canonical(session)+'.json')))
    if set(data)!={'rootfs','workspace','user'}:raise ValueError('Invalid prepared context')
    if not re.fullmatch(r'frw-[0-9a-f]{24}',data['user']):raise ValueError('Invalid workload identity')
    import pwd,grp
    account=pwd.getpwnam(data['user'])
    if account.pw_uid==0 or account.pw_shell!='/usr/sbin/nologin' or account.pw_dir!='/nonexistent':raise ValueError('Unsafe workload identity')
    if any(data['user'] in group.gr_mem for group in grp.getgrall()):raise ValueError('Supplementary workload groups forbidden')
    for name in ('rootfs','workspace'):
        path=pathlib.Path(data[name])
        if not path.is_absolute() or str(path)!=str(path.resolve()) or not str(path).startswith('/srv/forge-remote/'):
            raise ValueError('Unprepared workload path')
        for parent in path.parents:
            info=parent.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid!=0 or info.st_mode & 0o022:raise ValueError('Unsafe context ancestor')
        info=path.lstat()
        if not stat.S_ISDIR(info.st_mode):raise ValueError('Prepared directory required')
        if name=='rootfs' and (info.st_uid!=0 or info.st_mode & 0o022):raise ValueError('Unsafe root filesystem')
        if name=='workspace' and (info.st_uid!=account.pw_uid or info.st_mode & 0o077):raise ValueError('Unsafe workspace')
    if data['rootfs']==data['workspace']:raise ValueError('Separate immutable root required')
    return data


def command_argv(session,execution,context,request,fence):
    request=command_request(request)
    # systemd specifiers in operator-owned paths/cwd must remain literal.
    literal=lambda value:value.replace('%','%%')
    properties={
        'ConditionPathExists':str(fence),
        'User':context['user'],'Group':context['user'],'RootDirectory':context['rootfs'],
        'WorkingDirectory':request['cwd'],'BindPaths':context['workspace']+':/workspace',
        'KillMode':'control-group','TimeoutStopSec':'5s','SendSIGKILL':'yes','Delegate':'no',
        'NoNewPrivileges':'yes','CapabilityBoundingSet':'','AmbientCapabilities':'',
        'ProtectSystem':'strict','ReadWritePaths':'/workspace','ProtectHome':'yes','PrivateTmp':'yes',
        'PrivateDevices':'yes','PrivateNetwork':'yes','RestrictSUIDSGID':'yes','ProtectControlGroups':'yes',
        'ProtectKernelTunables':'yes','ProtectKernelModules':'yes','ProtectKernelLogs':'yes',
        'RestrictNamespaces':'yes','RestrictRealtime':'yes','LockPersonality':'yes',
        'ProtectProc':'invisible','ProcSubset':'pid','UMask':'0077','TasksMax':'128',
        'MemoryMax':'1G','RuntimeMaxSec':str(request['timeoutSeconds'])+'s',
        'BindsTo':'forge-remote-workloads.service','After':'forge-remote-workloads.service',
    }
    argv=['/usr/bin/systemd-run','--quiet','--pipe','--wait','--collect','--service-type=exec',
          '--expand-environment=no','--unit='+unit_name(session,execution),
          '--slice=forge-remote-'+canonical(session).replace('-','')+'.slice']
    for key,value in properties.items():argv+=['--property',key+'='+literal(value)]
    return argv+['--']+request['argv']
