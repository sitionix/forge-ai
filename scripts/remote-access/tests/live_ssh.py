"""Disposable real sshd + installed production helper/config. Authority is explicitly stubbed."""
import base64
import hashlib
import importlib.util
import os
import pathlib
import pwd
import socket
import stat
import struct
import subprocess as sp
import threading
import time
import uuid

BASE=pathlib.Path('/opt/forge-remote-package')
spec=importlib.util.spec_from_file_location('setup',BASE/'install.py')
setup=importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)

def run(*args):
    return sp.run(args,stdin=sp.DEVNULL,stdout=sp.PIPE,stderr=sp.PIPE,timeout=12)

def check(condition,label):
    if not condition: raise AssertionError(label)
    print('PASS '+label,flush=True)

installation=run('/usr/bin/python3','-I',str(BASE/'install.py'),'--listen-address','127.0.0.1','--port','22222')
check(installation.returncode!=0 and b'system systemd manager unavailable' in installation.stderr,
      'real installer reports NOT READY without systemd manager')
setup.prepare('127.0.0.1',22222)
check(run('systemd-analyze','verify','/etc/systemd/system/forge-remote-sshd.service').returncode==0,
      'installed systemd unit passes static verification')
# Reboot-style missing runtime directory: only this installation's tmpfiles may restore it.
pathlib.Path('/run/sshd').rmdir()
check(run('/usr/sbin/sshd','-t','-f','/etc/forge-remote/sshd_config').returncode!=0,
      'missing privilege-separation directory prevents daemon startup')
check(run('systemd-tmpfiles','--create','/etc/tmpfiles.d/forge-remote.conf').returncode==0,
      'managed tmpfiles applies without unrelated host sshd')
check(run('/usr/sbin/sshd','-t','-f','/etc/forge-remote/sshd_config').returncode==0,
      'managed boot setup restores privilege-separation directory')
# Exercise actual old-package helper upgrade under root, with existing host identity preserved.
installed_helper=pathlib.Path('/usr/libexec/forge-remote/forced-command')
installed_helper.write_bytes((BASE/'tests'/'fixtures'/'stage2-forced-command.py').read_bytes())
installed_helper.chmod(0o755)
original=(pathlib.Path('/etc/forge-remote/host_ed25519').read_bytes(),
          pathlib.Path('/etc/ssh/sshd_config').read_bytes())
setup.prepare('127.0.0.1',22222)
check(installed_helper.read_bytes()==(BASE/'forced_command.py').read_bytes(),'known Stage 2 helper upgrades atomically to current package')
check(original==(pathlib.Path('/etc/forge-remote/host_ed25519').read_bytes(),
                 pathlib.Path('/etc/ssh/sshd_config').read_bytes()),'repeat setup preserves host key and unrelated sshd config')
fixture=pathlib.Path('/fixture');fixture.mkdir(mode=0o700)
for name in ['session','foreign','wrong-host','pairing']:
    result=run('ssh-keygen','-q','-t','ed25519','-N','','-f',str(fixture/name))
    check(result.returncode==0,'generate synthetic '+name)
key=(fixture/'session.pub').read_text().split()[:2]
blob=base64.b64decode(key[1]);digest=hashlib.sha256(blob).digest()
fingerprint='SHA256:'+base64.b64encode(digest).decode().rstrip('=')
grantor=str(uuid.uuid4());session=str(uuid.uuid4())
peer=pwd.getpwnam('forge-ssh');control=pwd.getpwnam('forge-control')
# Test fixture provisions one root-owned binding; production installation creates no grant.
record=pathlib.Path('/var/lib/forge-remote/bindings')/digest.hex()
record.write_text('session '+grantor+' '+session+' '+fingerprint+'\n');record.chmod(0o640);os.chown(record,0,peer.pw_gid)
keys=pathlib.Path('/var/lib/forge-remote/authorized/keys')
# Deliberately omit command= and restrict: daemon-wide policy must still close bypasses.
keys.write_text(' '.join(key)+'\n');keys.chmod(0o640);os.chown(keys,0,peer.pw_gid)
for source,target in [(pathlib.Path('/etc/forge-remote/host_ed25519.pub'),'known'),(fixture/'wrong-host.pub','wrong-known')]:
    (fixture/target).write_text('[127.0.0.1]:22222 '+' '.join(source.read_text().split()[:2])+'\n')
    (fixture/target).chmod(0o600)
# Real separate UID process supplies the stub authority, matching helper SO_PEERCRED checks.
authority_code='''import os,socket,pathlib
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.bind('/run/forge-remote/channel/authority.sock');os.chmod('/run/forge-remote/channel/authority.sock',0o660)
s.listen()
while True:
 c,_=s.accept()
 with c:
  request=c.recv(1024).decode()
  mode=pathlib.Path('/run/forge-remote/channel/test-status').read_text().strip()
  expected=pathlib.Path('/run/forge-remote/channel/test-request').read_text()
  c.sendall((mode+'\\n' if request==expected else 'DENIED\\n').encode())
'''
state=pathlib.Path('/run/forge-remote/channel/test-status');state.write_text('ACTIVE');state.chmod(0o644)
pathlib.Path('/run/forge-remote/channel/test-request').write_text('STATUS '+grantor+' '+session+' '+fingerprint+'\n')
authority=sp.Popen(['python3','-c',authority_code],user=control.pw_uid,group=peer.pw_gid,extra_groups=[],stdout=sp.DEVNULL,stderr=sp.PIPE)
supervisor=None
sshd=sp.Popen(['/usr/sbin/sshd','-D','-e','-f','/etc/forge-remote/sshd_config'],stdout=sp.DEVNULL,stderr=sp.PIPE)
try:
    for _ in range(60):
        try:
            with socket.create_connection(('127.0.0.1',22222),timeout=.1): pass
            if pathlib.Path('/run/forge-remote/channel/authority.sock').exists(): break
        except OSError: pass
        time.sleep(.05)
    def ssh(*args,identity='session',known='known'):
        return run('ssh','-F','/dev/null','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','IdentityAgent=none',
                   '-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(fixture/known),
                   '-o','GlobalKnownHostsFile=/dev/null','-o','PasswordAuthentication=no',
                   '-o','KbdInteractiveAuthentication=no','-o','ControlMaster=no','-o','ControlPath=none',
                   '-i',str(fixture/identity),'-p','22222',*args)
    result=ssh('forge-ssh@127.0.0.1','status')
    check(result.returncode==0 and result.stdout==b'ACTIVE\n','real authenticated key reaches bound status: '+repr((result.returncode,result.stdout,result.stderr)))
    check(ssh('forge-ssh@127.0.0.1','status',known='wrong-known').returncode!=0,'wrong host pin denied')
    check(ssh('forge-ssh@127.0.0.1','status',identity='foreign').returncode!=0,'foreign session key denied')
    injected=ssh('-o','SetEnv=SSH_USER_AUTH=/etc/passwd PYTHONPATH=/tmp', 'forge-ssh@127.0.0.1','status')
    check(injected.returncode==0 and injected.stdout==b'ACTIVE\n','peer environment cannot replace authenticated key proof')
    for command in ['id','sh','status '+str(uuid.uuid4()),'confirm','']:
        check(ssh('forge-ssh@127.0.0.1',command).returncode!=0,'arbitrary or forged command denied: '+repr(command))
    check(ssh('-s','forge-ssh@127.0.0.1','sftp').returncode!=0,'sftp subsystem denied')
    pty=ssh('-tt','forge-ssh@127.0.0.1','status')
    check(b'PTY allocation request failed' in pty.stderr,'PTY denied')
    check(ssh('-W','127.0.0.1:22222','forge-ssh@127.0.0.1').returncode!=0,'TCP forwarding denied')
    for status in ['PROVISIONING','DENIED']:
        state.write_text(status)
        result=ssh('forge-ssh@127.0.0.1','status')
        check((result.returncode==0 and result.stdout==b'PROVISIONING\n') if status=='PROVISIONING' else result.returncode!=0,
              'current authority status '+status)
    record.write_text('session '+grantor+' '+str(uuid.uuid4())+' '+fingerprint+'\n')
    check(ssh('forge-ssh@127.0.0.1','status').returncode!=0,'binding mismatch denied by authority')
    record.write_text('session '+grantor+' '+session+' '+fingerprint+'\n')
    # Stage 3: real root supervisor and control UID provision an invitation-only key.
    admin=pathlib.Path('/run/forge-remote/admin')
    admin.mkdir(mode=0o750); admin.chmod(0o750); os.chown(admin,0,control.pw_gid)
    supervisor=sp.Popen(['/usr/libexec/forge-remote/invitation-supervisor'],stdout=sp.DEVNULL,stderr=sp.PIPE)
    for _ in range(60):
        if (admin/'invitations.sock').exists(): break
        if supervisor.poll() is not None: raise AssertionError('supervisor failed to start')
        time.sleep(.05)
    control_code="""import socket,sys
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.settimeout(3)
s.connect('/run/forge-remote/admin/invitations.sock')
s.sendall(sys.argv[1].encode())
print(s.recv(2048).decode(),end='')
"""
    def admin_request(frame, account=control):
        return sp.run(['python3','-c',control_code,frame],user=account.pw_uid,group=account.pw_gid,
                      extra_groups=[],stdout=sp.PIPE,stderr=sp.PIPE,timeout=5)
    host=admin_request('HOST\n')
    check(host.returncode==0 and host.stdout.strip()==b' '.join(pathlib.Path('/etc/forge-remote/host_ed25519.pub').read_bytes().split()[:2]),
          'control UID reads only managed host public identity')
    check(admin_request('HOST\n',peer).returncode!=0,'transport UID cannot reach privileged invitation socket')
    pairing_key=' '.join((fixture/'pairing.pub').read_text().split()[:2])
    invitation=str(uuid.uuid4())
    install='INSTALL '+grantor+' '+invitation+' '+pairing_key+'\n'
    check(admin_request(install).stdout==b'OK\n','real supervisor installs only invitation public grant')
    check(admin_request(install).stdout==b'OK\n','repeated invitation installation is idempotent')
    pairing_fp='SHA256:'+base64.b64encode(hashlib.sha256(base64.b64decode(pairing_key.split()[1])).digest()).decode().rstrip('=')
    state.write_text('PAIRING_ALLOWED')
    expected_path=pathlib.Path('/run/forge-remote/channel/test-request')
    expected_path.write_text('PAIR '+grantor+' '+invitation+' '+pairing_fp+'\n')
    paired=ssh('forge-ssh@127.0.0.1','pair',identity='pairing')
    check(paired.returncode==0 and paired.stdout==b'PAIRING_ALLOWED\n','pairing key reaches only its bound invitation authority')
    for operation in ['status','id','confirm','pair '+str(uuid.uuid4())]:
        check(ssh('forge-ssh@127.0.0.1',operation,identity='pairing').returncode!=0,'pairing key denied operation '+operation.split()[0])
    check(b'PTY allocation request failed' in ssh('-tt','forge-ssh@127.0.0.1','pair',identity='pairing').stderr,'pairing key cannot allocate PTY')
    check(ssh('-W','127.0.0.1:22222','forge-ssh@127.0.0.1',identity='pairing').returncode!=0,'pairing key cannot forward')
    state.write_text('DENIED')
    check(ssh('forge-ssh@127.0.0.1','pair',identity='pairing').returncode!=0,'current authority denial closes already installed invitation')
    remove='REMOVE '+grantor+' '+invitation+' '+pairing_key+'\n'
    check(admin_request(remove).stdout==b'OK\n','supervisor removes invitation grant')
    state.write_text('PAIRING_ALLOWED')
    check(ssh('forge-ssh@127.0.0.1','pair',identity='pairing').returncode!=0,'removed invitation key cannot authenticate again')
    state.write_text('ACTIVE')
    expected_path.write_text('STATUS '+grantor+' '+session+' '+fingerprint+'\n')
    check(ssh('forge-ssh@127.0.0.1','status').returncode==0,'invitation cleanup preserves independent session grant')
    check(run('systemd-analyze','verify','/etc/systemd/system/forge-remote-invitations.service').returncode==0,
          'invitation supervisor systemd unit passes static verification')
    authority.terminate();authority.wait(timeout=5)
    check(ssh('forge-ssh@127.0.0.1','status').returncode!=0,'unavailable authority denied')
    for target in ['/var/lib/forge-remote/authorized/keys','/usr/libexec/forge-remote/forced-command','/etc/forge-remote/host_ed25519']:
        result=sp.run(['python3','-c','import pathlib,sys;pathlib.Path(sys.argv[1]).write_text("overwrite")',target],
                      user=peer.pw_uid,group=peer.pw_gid,extra_groups=[],stdout=sp.DEVNULL,stderr=sp.DEVNULL)
        check(result.returncode!=0,'transport cannot modify '+target)
    print('STAGE3_INVITATION_SSH_PASS: real supervisor/sshd/helper, stub Agent authority; no session activation',flush=True)
    print('STAGE2_SSH_BOUNDARY_PASS: real sshd/helper, stub authority; no workload execution or live Codex',flush=True)
finally:
    if supervisor is not None and supervisor.poll() is None: supervisor.terminate();supervisor.wait(timeout=5)
    if authority.poll() is None: authority.terminate();authority.wait(timeout=5)
    sshd.terminate();sshd.wait(timeout=5)
