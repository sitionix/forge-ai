"""Disposable real sshd + installed production helper/config. Authority is explicitly stubbed."""
import base64
import hashlib
import importlib.util
import json
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

def run(*args, payload=None):
    return sp.run(args,input=payload,stdin=sp.DEVNULL if payload is None else None,stdout=sp.PIPE,stderr=sp.PIPE,timeout=12)

def check(condition,label):
    if not condition: raise AssertionError(label)
    print('PASS '+label,flush=True)

installation=run('/usr/bin/python3','-I',str(BASE/'install.py'))
check(installation.returncode!=0 and b'system systemd manager unavailable' in installation.stderr,
      'real installer reports NOT READY without systemd manager')
setup.prepare()
check(not pathlib.Path('/etc/systemd/system/forge-remote-sshd.service').exists(),
      'system SSH integration installs no second daemon unit')
# Reboot-style missing runtime directory: only this installation's tmpfiles may restore it.
pathlib.Path('/run/sshd').rmdir()
check(run('/usr/sbin/sshd','-t').returncode!=0,
      'missing privilege-separation directory prevents daemon startup')
check(run('systemd-tmpfiles','--create','/etc/tmpfiles.d/forge-remote.conf').returncode==0,
      'managed tmpfiles restores system SSH privilege-separation directory')
check(run('/usr/sbin/sshd','-t').returncode==0,
      'system SSH configuration validates after setup')
# Exercise actual old-package helper upgrade under root, with existing host identity preserved.
installed_helper=pathlib.Path('/usr/libexec/forge-remote/forced-command')
installed_helper.write_bytes((BASE/'tests'/'fixtures'/'stage2-forced-command.py').read_bytes())
installed_helper.chmod(0o755)
original=(pathlib.Path('/etc/ssh/ssh_host_ed25519_key').read_bytes(),
          pathlib.Path('/etc/ssh/sshd_config').read_bytes())
setup.prepare()
check(installed_helper.read_bytes()==(BASE/'forced_command.py').read_bytes(),'known Stage 2 helper upgrades atomically to current package')
check(original==(pathlib.Path('/etc/ssh/ssh_host_ed25519_key').read_bytes(),
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
keys=pathlib.Path('/var/lib/forge-remote/transport-home/.ssh/authorized_keys')
keys.write_text('restrict,command="/usr/libexec/forge-remote/forced-command '+digest.hex()+'" '+' '.join(key)+'\n')
keys.chmod(0o640);os.chown(keys,0,peer.pw_gid)
for source,target in [(pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub'),'known'),(fixture/'wrong-host.pub','wrong-known')]:
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
  request=b''
  while len(request)<8192 and not request.endswith(b'\\n'):
   chunk=c.recv(8192-len(request))
   if not chunk: break
   request+=chunk
  request=request.decode()
  mode=pathlib.Path('/run/forge-remote/channel/test-status').read_text().strip()
  expected=pathlib.Path('/run/forge-remote/channel/test-request').read_text()
  c.sendall((mode+'\\n' if request==expected else 'DENIED\\n').encode())
'''
state=pathlib.Path('/run/forge-remote/channel/test-status');state.write_text('ACTIVE');state.chmod(0o644)
pathlib.Path('/run/forge-remote/channel/test-request').write_text('STATUS '+grantor+' '+session+' '+fingerprint+'\n')
authority=sp.Popen(['python3','-c',authority_code],user=control.pw_uid,group=peer.pw_gid,extra_groups=[],stdout=sp.DEVNULL,stderr=sp.PIPE)
supervisor=None
sshd=sp.Popen(['/usr/sbin/sshd','-D','-e','-p','22222'],stdout=sp.DEVNULL,stderr=sp.PIPE)
try:
    for _ in range(60):
        try:
            with socket.create_connection(('127.0.0.1',22222),timeout=.1): pass
            if pathlib.Path('/run/forge-remote/channel/authority.sock').exists(): break
        except OSError: pass
        time.sleep(.05)
    def ssh(*args,identity='session',known='known',payload=None):
        return run('ssh','-F','/dev/null','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','IdentityAgent=none',
                   '-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(fixture/known),
                   '-o','GlobalKnownHostsFile=/dev/null','-o','PasswordAuthentication=no',
                   '-o','KbdInteractiveAuthentication=no','-o','ControlMaster=no','-o','ControlPath=none',
                   '-i',str(fixture/identity),'-p','22222',*args,payload=payload)
    result=ssh('forge-ssh@127.0.0.1','status')
    check(result.returncode==0 and result.stdout==b'ACTIVE\n','real authenticated key reaches bound status: '+repr((result.returncode,result.stdout,result.stderr)))
    check(ssh('forge-ssh@127.0.0.1','status',known='wrong-known').returncode!=0,'wrong host pin denied')
    check(ssh('forge-ssh@127.0.0.1','status',identity='foreign').returncode!=0,'foreign session key denied')
    injected=ssh('-o','SetEnv=PYTHONPATH=/tmp', 'forge-ssh@127.0.0.1','status')
    check(injected.returncode==0 and injected.stdout==b'ACTIVE\n','peer environment cannot replace root-owned key binding')
    for command in ['id','sh','status '+str(uuid.uuid4()),'confirm '+str(uuid.uuid4()),'exec id','revoke','']:
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
    check(host.returncode==0 and host.stdout.strip()==b' '.join(pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub').read_bytes().split()[:2]),
          'control UID reads system SSH host public identity')
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
    # Stage 4: invitation stdin is bounded metadata; authenticated session key confirms.
    payload=json.dumps({'sessionId':session,'accessorInstanceId':str(uuid.uuid4()),
                        'accessorDisplayName':'Test accessor','sessionPublicKey':' '.join(key)},separators=(',',':')).encode()
    encoded=base64.urlsafe_b64encode(payload).decode().rstrip('=')
    expected_path.write_text('REDEEM '+grantor+' '+invitation+' '+pairing_fp+' '+encoded+'\n')
    state.write_text('PROVISIONING '+session)
    redeemed=ssh('forge-ssh@127.0.0.1','redeem',identity='pairing',payload=payload)
    check(redeemed.returncode==0 and redeemed.stdout==('PROVISIONING '+session+'\n').encode(),
          'invitation redeem forwards bounded JSON with authenticated immutable binding')
    check(ssh('forge-ssh@127.0.0.1','redeem',identity='pairing',payload=b'{}'+b' '*6000).returncode!=0,
          'oversized redeem input denied')
    expected_path.write_text('REDEEM '+grantor+' '+str(uuid.uuid4())+' '+pairing_fp+' '+encoded+'\n')
    check(ssh('forge-ssh@127.0.0.1','redeem',identity='pairing',payload=payload).returncode!=0,
          'redeem invitation binding mismatch denied')
    expected_path.write_text('CONFIRM '+grantor+' '+session+' '+fingerprint+'\n')
    state.write_text('ACTIVE')
    confirmed=ssh('forge-ssh@127.0.0.1','confirm')
    check(confirmed.returncode==0 and confirmed.stdout==b'ACTIVE\n','new session key confirms its own immutable binding')
    state.write_text('PROVISIONING')
    check(ssh('forge-ssh@127.0.0.1','confirm').returncode!=0,'confirm cannot accept provisioning as active')
    state.write_text('ACTIVE')
    expected_path.write_text('CONFIRM '+grantor+' '+str(uuid.uuid4())+' '+fingerprint+'\n')
    check(ssh('forge-ssh@127.0.0.1','confirm').returncode!=0,'confirm binding mismatch denied')
    session_key=' '.join((fixture/'foreign.pub').read_text().split()[:2])
    installed_session=str(uuid.uuid4())
    session_install='SESSION_INSTALL '+grantor+' '+installed_session+' '+session_key+'\n'
    check(admin_request(session_install).stdout==b'OK\n','root supervisor installs restricted session grant')
    check(admin_request(session_install).stdout==b'OK\n','session grant install is idempotent')
    session_fp='SHA256:'+base64.b64encode(hashlib.sha256(base64.b64decode(session_key.split()[1])).digest()).decode().rstrip('=')
    expected_path.write_text('CONFIRM '+grantor+' '+installed_session+' '+session_fp+'\n')
    check(ssh('forge-ssh@127.0.0.1','confirm',identity='foreign').returncode==0,'supervisor session grant reaches confirm')
    for operation in ['pair','redeem','exec id','revoke']:
        check(ssh('forge-ssh@127.0.0.1',operation,identity='foreign').returncode!=0,'session denies '+operation)
    # Upgrade exact Stage 3 bytes while retaining live grants and all root bindings.
    saved_keys=keys.read_bytes()
    binding_dir=pathlib.Path('/var/lib/forge-remote/bindings')
    saved_bindings={p.name:p.read_bytes() for p in binding_dir.iterdir()}
    supervisor.terminate();supervisor.wait(timeout=5)
    (admin/'invitations.sock').unlink()
    for target,fixture_name in [(installed_helper,'stage3-forced-command.py'),
                                (pathlib.Path('/usr/libexec/forge-remote/invitation-supervisor'),'stage3-invitation-supervisor.py')]:
        target.write_bytes((BASE/'tests'/'fixtures'/fixture_name).read_bytes());target.chmod(0o755)
    # Old supervisor bytes cannot serve the new system-SSH key path. Refuse
    # their replacement while the protected runtime directory still exists.
    try:
        setup.prepare()
    except RuntimeError as failure:
        check('stop managed invitation supervisor' in str(failure),'present supervisor runtime blocks upgrade before artifact changes')
    else: raise AssertionError('old supervisor upgrade was allowed with runtime directory present')
    check(installed_helper.read_bytes()==(BASE/'tests'/'fixtures'/'stage3-forced-command.py').read_bytes(),
          'blocked upgrade preserves old forced helper')
    admin.rmdir()  # emulate systemd RuntimeDirectory cleanup after stop
    setup.prepare()
    admin.mkdir(mode=0o750);os.chown(admin,0,control.pw_gid)
    supervisor=sp.Popen(['/usr/libexec/forge-remote/invitation-supervisor'],stdout=sp.DEVNULL,stderr=sp.PIPE)
    for _ in range(60):
        if (admin/'invitations.sock').exists(): break
        if supervisor.poll() is not None: raise AssertionError('upgraded supervisor failed to start')
        time.sleep(.05)
    check(admin_request(session_install).stdout==b'OK\n','restarted upgraded supervisor accepts Stage 4 session grant')
    check(keys.read_bytes()==saved_keys and {p.name:p.read_bytes() for p in binding_dir.iterdir()}==saved_bindings,
          'exact Stage 3 helper and supervisor upgrade preserves existing session and invitation grants')
    check(installed_helper.read_bytes()==(BASE/'forced_command.py').read_bytes() and
          pathlib.Path('/usr/libexec/forge-remote/invitation-supervisor').read_bytes()==(BASE/'invitation_supervisor.py').read_bytes(),
          'both exact Stage 3 executables upgraded')
    check(admin_request('SESSION_REMOVE '+grantor+' '+session+' '+session_key+'\n').stdout==b'DENIED\n',
          'session removal cannot override immutable session identity')
    check(admin_request('SESSION_REMOVE '+grantor+' '+installed_session+' '+session_key+'\n').stdout==b'OK\n',
          'supervisor removes only owned session grant')
    check(ssh('forge-ssh@127.0.0.1','confirm',identity='foreign').returncode!=0,'removed session key cannot authenticate')
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
    for target in ['/var/lib/forge-remote/transport-home/.ssh/authorized_keys','/usr/libexec/forge-remote/forced-command','/etc/ssh/ssh_host_ed25519_key']:
        result=sp.run(['python3','-c','import pathlib,sys;pathlib.Path(sys.argv[1]).write_text("overwrite")',target],
                      user=peer.pw_uid,group=peer.pw_gid,extra_groups=[],stdout=sp.DEVNULL,stderr=sp.DEVNULL)
        check(result.returncode!=0,'transport cannot modify '+target)
    print('STAGE4_PAIRING_SSH_PASS: real supervisor/sshd/helper, stub Agent authority; redeem and confirm routing only',flush=True)
    print('STAGE3_INVITATION_SSH_PASS: real supervisor/sshd/helper, stub Agent authority; no session activation',flush=True)
    print('STAGE2_SSH_BOUNDARY_PASS: real sshd/helper, stub authority; no workload execution or live Codex',flush=True)
finally:
    if supervisor is not None and supervisor.poll() is None: supervisor.terminate();supervisor.wait(timeout=5)
    if authority.poll() is None: authority.terminate();authority.wait(timeout=5)
    sshd.terminate();sshd.wait(timeout=5)
