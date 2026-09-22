"""Stage 0 ONLY: disposable real SSH -> stub supervisor -> system systemd units.
Run interactively as root with an exported probe-image tar path. No production
Forge state/gate is implemented. Never install these fixtures as runtime helpers.
"""
import argparse
import json
import os
from pathlib import Path
import pwd
import shutil
import socket
import struct
import subprocess as sp
import tempfile
import threading
import time
import uuid


def run(*argv, timeout=30):
    return sp.run(argv, stdin=sp.DEVNULL, capture_output=True, text=True, timeout=timeout)


def checked(*argv, timeout=30):
    r = run(*argv, timeout=timeout)
    if r.returncode:
        raise RuntimeError(str(argv[0]) + ': ' + r.stderr.strip())
    return r.stdout.strip()


def check(condition, label):
    if not condition:
        raise AssertionError(label)
    print('PASS ' + label, flush=True)


def write(path, value, mode=0o644):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value)
    path.chmod(mode)


def cleanup(units, base, prefix):
    """Remove owned artifacts only after every bounded systemd check confirms safety."""
    failures = []
    for unit in units:
        stop_result = None
        try:
            stop_result = run('systemctl', 'stop', unit)
        except Exception as exc:
            failures.append(unit + ': stop ' + type(exc).__name__)
        # Inspect even after a failed stop; still attempt all remaining units.
        try:
            state = run('systemctl', 'show', unit, '--property=LoadState',
                        '--property=ActiveState', '--property=SubState', '--property=MainPID')
            if state.returncode != 0:
                failures.append(unit + ': inspection exit ' + str(state.returncode))
                continue
            fields = {}
            for line in state.stdout.splitlines():
                key, separator, value = line.partition('=')
                if not separator or key in fields:
                    raise ValueError('invalid systemd properties')
                fields[key] = value
            if set(fields) != {'LoadState', 'ActiveState', 'SubState', 'MainPID'}:
                raise ValueError('incomplete systemd properties')
            stopped = (fields['ActiveState'] == 'inactive'
                       and fields['SubState'] == 'dead' and fields['MainPID'] == '0')
            # show returns success + explicit not-found for absent transient units.
            # A failed stop is tolerated only for that positively confirmed absence.
            absent = fields['LoadState'] == 'not-found'
            if not stopped or fields['LoadState'] not in {'loaded', 'not-found'}:
                failures.append(unit + ': stopped/absent state not confirmed')
            elif stop_result is not None and stop_result.returncode != 0 and not absent:
                failures.append(unit + ': stop exit ' + str(stop_result.returncode))
        except Exception as exc:
            failures.append(unit + ': inspection ' + type(exc).__name__)
    if failures:
        message = ('CLEANUP_FAILED: retained ' + str(base)
                   + '; state directories/symlinks retained; ' + '; '.join(failures))
        print(message, flush=True)
        raise RuntimeError(message)
    # reset-failed is deliberately unnecessary: it is not evidence of process stop.
    for role in ['a', 'b']:
        state_dir = Path('/var/lib/private') / (prefix + '-' + role)
        state_link = Path('/var/lib') / (prefix + '-' + role)
        if state_link.is_symlink():
            state_link.unlink()
        if state_dir.exists():
            shutil.rmtree(state_dir)
    shutil.rmtree(base)
    print('CLEANUP_PASS: only owned units/rootfs removed', flush=True)


def main(archive):
    if os.geteuid() != 0:
        raise RuntimeError('NOT READY: interactive root authorization required')
    prefix = 'forge-stage0-' + uuid.uuid4().hex[:12]
    base = Path(tempfile.mkdtemp(prefix=prefix + '-', dir='/var/tmp'))
    root = base / 'root'
    root.mkdir()
    units = [prefix + '-ssh.service', prefix + '-a.service', prefix + '-b.service']
    server = None
    admin = None
    stop = threading.Event()
    worker = None
    # Never share a host account. Refuse UID collision, including live processes.
    uids = [62001, 62002, 62003]
    for uid in uids:
        try:
            pwd.getpwuid(uid)
        except KeyError:
            pass
        else:
            raise RuntimeError('NOT READY: reserved probe UID already allocated')
    for status in Path('/proc').glob('[0-9]*/status'):
        try:
            values = next(line for line in status.read_text().splitlines() if line.startswith('Uid:')).split()[1:]
            if any(int(v) in uids for v in values):
                raise RuntimeError('NOT READY: probe UID currently in use')
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            pass
    try:
        checked('tar', '--extract', '--file', archive, '--directory', str(root), '--no-same-owner', timeout=120)
        for directory in ['run/sshd', 'probe', 'workspace-a', 'workspace-b', 'home/forgepeer']:
            (root / directory).mkdir(parents=True, exist_ok=True)
        with (root / 'etc/passwd').open('a') as f:
            f.write('forgepeer:x:62001:62001:Probe only:/home/forgepeer:/bin/sh\n')
        with (root / 'etc/group').open('a') as f:
            f.write('forgepeer:x:62001:\n')
        with (root / 'etc/shadow').open('a') as f:
            f.write('forgepeer:*:20000:0:99999:7:::\n')
        for role in ['a', 'b']:
            os.chown(root / ('workspace-' + role), uids[1 if role == 'a' else 2], uids[1 if role == 'a' else 2])
            (root / ('workspace-' + role)).chmod(0o700)
        for name in ['host', 'wrong', 'pair', 'a', 'b']:
            checked('ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(base / name))
        shutil.copy(base / 'host', root / 'probe/host')
        (root / 'probe/host').chmod(0o600)
        write(root / 'probe/control-canary', 'synthetic control secret', 0o600)
        helper = '''#!/usr/local/bin/python3
import socket, sys
role=sys.argv[1]
if role == 'pair':
 print('PAIRING_ONLY')
else:
 s=socket.socket(socket.AF_UNIX)
 s.connect('/probe/admission.sock')
 s.sendall(role.encode())
 print(s.recv(256).decode())
'''
        write(root / 'probe/helper', helper, 0o755)
        auth = ''
        for role in ['pair', 'a', 'b']:
            auth += 'restrict,command="/probe/helper ' + role + '" ' + (base / (role + '.pub')).read_text()
        write(root / 'probe/authorized', auth)
        write(root / 'probe/sshd.conf', '''Port 22222
ListenAddress 127.0.0.1
HostKey /probe/host
PidFile /run/sshd/probe.pid
AuthorizedKeysFile /probe/authorized
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
PermitRootLogin no
AllowUsers forgepeer
PermitTTY no
DisableForwarding yes
PermitUserEnvironment no
PermitUserRC no
LogLevel ERROR
''')
        for name, out in [('host', 'known'), ('wrong', 'wrong-known')]:
            public = ' '.join((base / (name + '.pub')).read_text().split()[:2])
            write(base / out, '[127.0.0.1]:22222 ' + public + '\n', 0o600)
        admin = socket.socket()
        admin.bind(('127.0.0.1', 0))
        admin.listen(2)
        admin_port = admin.getsockname()[1]
        with socket.create_connection(('127.0.0.1', admin_port), timeout=1):
            check(True, 'synthetic admin listener reachable from host')
        # Synthetic host-only admin listener and canary; never touch real secrets/API.
        workload = '''import json, os, pathlib, socket, subprocess, time
root=pathlib.Path('/workspace-ROLE')
results={}
for label,path,mode in [('auth_write','/probe/authorized','a'),('helper_write','/probe/helper','a'),('control_read','/probe/control-canary','r'),('host_key_read','/probe/host','r')]:
 try:
  with open(path,mode): pass
  results[label]=False
 except (PermissionError,FileNotFoundError,OSError): results[label]=True
try:
 s=socket.socket(socket.AF_UNIX); s.connect('/probe/admission.sock'); results['socket_denied']=False
except OSError: results['socket_denied']=True
try:
 s=socket.create_connection(('127.0.0.1',ADMIN_PORT),timeout=1); s.close(); results['admin_denied']=False
except OSError: results['admin_denied']=True
results['no_docker_socket']=not pathlib.Path('/var/run/docker.sock').exists()
results['no_system_bus']=not pathlib.Path('/run/dbus/system_bus_socket').exists()
results['other_workspace_denied']=not os.access('/workspace-OTHER',os.R_OK|os.W_OK)
child=subprocess.Popen(['sleep','300'])
escaped=subprocess.Popen(['setsid','sleep','300'])
(root/'report.json').write_text(json.dumps({'checks':results,'pids':[os.getpid(),child.pid,escaped.pid]}))
time.sleep(300)
'''
        for role, other in [('a', 'b'), ('b', 'a')]:
            write(root / ('probe/workload-' + role + '.py'), workload.replace("'/workspace-ROLE'", repr('/var/lib/'+prefix+'-'+role)).replace('OTHER', other).replace('ADMIN_PORT',str(admin_port)))
        server = socket.socket(socket.AF_UNIX)
        server.bind(str(root / 'probe/admission.sock'))
        os.chown(root / 'probe/admission.sock', 0, 62001)
        os.chmod(root / 'probe/admission.sock', 0o660)
        server.listen(4)
        server.settimeout(.2)
        admitted = set()
        errors = []
        def supervise():
            while not stop.is_set():
                try:
                    connection, _ = server.accept()
                except socket.timeout:
                    continue
                with connection:
                    try:
                        connection.settimeout(2)
                        _, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,12))
                        role = connection.recv(16).decode()
                        if uid != 62001 or role not in ['a','b'] or role in admitted:
                            connection.sendall(b'DENIED'); continue
                        index = 1 if role == 'a' else 2
                        args = ['systemd-run','--unit='+units[index], '--property=RootDirectory='+str(root),
                                '--property=DynamicUser=yes', '--property=User='+prefix+'-'+role,
                                '--property=StateDirectory='+prefix+'-'+role, '--property=StateDirectoryMode=0700',
                                '--property=PrivateNetwork=yes','--property=PrivateDevices=yes',
                                '--property=PrivateTmp=yes','--property=NoNewPrivileges=yes',
                                '--property=CapabilityBoundingSet=', '--property=ProtectSystem=strict',
                                '--property=ProtectHome=yes','--property=ProtectControlGroups=yes',
                                '--property=ProtectProc=invisible','--property=RestrictSUIDSGID=yes',
                                '--property=ReadWritePaths=/var/lib/'+prefix+'-'+role,
                                '--property=KillMode=control-group','--property=TimeoutStopSec=2s',
                                '/usr/local/bin/python3','/probe/workload-'+role+'.py']
                        checked(*args)
                        admitted.add(role)
                        connection.sendall(b'STARTED_'+role.encode())
                    except Exception as exc:
                        errors.append(str(exc))
                        try: connection.sendall(b'ERROR')
                        except OSError: pass
        worker = threading.Thread(target=supervise, daemon=True)
        worker.start()
        checked('systemd-run','--unit='+units[0], '--property=RootDirectory='+str(root),
                '--property=PrivateNetwork=yes','--property=KillMode=control-group',
                '--property=TimeoutStopSec=2s','/bin/sh','-c',
                'mkdir -p /run/sshd && exec /usr/sbin/sshd -D -e -f /probe/sshd.conf')
        ssh_pid = checked('systemctl','show',units[0],'--property=MainPID','--value')
        def ssh(key, extra=(), known='known'):
            return run('nsenter','--target',ssh_pid,'--net','ssh','-F','/dev/null',
                       '-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','IdentityAgent=none',
                       '-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(base/known),
                       '-o','GlobalKnownHostsFile=/dev/null','-o','ControlMaster=no','-o','ControlPath=none',
                       '-o','ConnectTimeout=2','-i',str(base/key),'-p','22222',*extra,
                       'forgepeer@127.0.0.1','touch /tmp/escape')
        for _ in range(30):
            r = ssh('pair')
            if r.returncode==0: break
            time.sleep(.2)
        if r.returncode: print('SSH fixture diagnostic: '+r.stderr.strip(), flush=True)
        check(r.returncode==0 and r.stdout.strip()=='PAIRING_ONLY', 'real pairing forced command; requested command ignored')
        check(not (root/'tmp/escape').exists(), 'no requested shell side effect')
        r=ssh('pair',known='wrong-known')
        check(r.returncode==255 and 'Host key verification failed' in r.stderr,'wrong host pin rejected')
        r=ssh('pair',('-tt',))
        check('PTY allocation request failed' in r.stderr,'pairing PTY rejected')
        r=ssh('pair',('-W','127.0.0.1:22222'))
        check(r.returncode==255 and 'administratively prohibited' in r.stderr,'pairing TCP forwarding rejected')
        for role in ['a','b']:
            r=ssh(role)
            check(r.returncode==0 and r.stdout.strip()=='STARTED_'+role, 'SSH session '+role+' admitted by UID-bound stub supervisor')
        check(not errors,'supervisor fixture has no errors')
        reports=[]
        for role in ['a','b']:
            report=Path('/var/lib/private')/(prefix+'-'+role)/'report.json'
            for _ in range(100):
                if report.exists(): break
                time.sleep(.1)
            data=json.loads(report.read_text())
            for label, good in data['checks'].items(): check(good, role+' '+label)
            reports.append(data)
        groups=[]
        workload_uids=[]
        network_namespaces=[]
        for data in reports:
            parent_pid=data['pids'][0]
            status=Path('/proc',str(parent_pid),'status').read_text().splitlines()
            uid=int(next(line for line in status if line.startswith('Uid:')).split()[1])
            workload_uids.append(uid)
            check(uid not in [0, 62001], 'workload identity differs from root and SSH control identity')
            network_namespaces.append(os.stat('/proc/'+str(parent_pid)+'/ns/net').st_ino)
        check(workload_uids[0]!=workload_uids[1], 'sessions have distinct actual workload UIDs')
        check(len(set(network_namespaces+[os.stat('/proc/self/ns/net').st_ino]))==3, 'workload network namespaces isolated from host and each other')
        for i,data in enumerate(reports):
            group=checked('systemctl','show',units[i+1],'--property=ControlGroup','--value')
            groups.append(group)
            for pid in data['pids']:
                check(Path('/proc',str(pid),'cgroup').read_text().strip()=='0::'+group,'parent/child/setsid in system-managed session cgroup')
        check(groups[0]!=groups[1],'sessions have independent cgroups')
        checked('systemctl','stop',units[1])
        for _ in range(100):
            if all(not Path('/proc',str(pid)).exists() for pid in reports[0]['pids']): break
            time.sleep(.05)
        check(all(not Path('/proc',str(pid)).exists() for pid in reports[0]['pids']),'A parent/background/setsid terminated')
        check(all(Path('/proc',str(pid)).exists() for pid in reports[1]['pids']),'B remains alive after A cleanup')
        print('BOUNDARY_PROBE_PASS: real SSH + stub supervisor + distinct UIDs + system systemd; NOT Forge runtime E2E',flush=True)
    finally:
        stop.set()
        if worker: worker.join(timeout=35)
        if server: server.close()
        if admin: admin.close()
        cleanup(units, base, prefix)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('image_tar')
    main(parser.parse_args().image_tar)
