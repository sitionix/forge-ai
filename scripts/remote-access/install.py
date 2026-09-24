#!/usr/bin/python3 -I
"""Narrow privileged installation. Never alters personal or host sshd configuration."""
import argparse
import grp
import hashlib
import tempfile
import ipaddress
import os
import pathlib
import platform
import pwd
import shutil
import socket
import stat
import subprocess
import sys

ETC = pathlib.Path('/etc/forge-remote')
LIB = pathlib.Path('/usr/libexec/forge-remote')
STATE = pathlib.Path('/var/lib/forge-remote')
RUN = pathlib.Path('/run/forge-remote')
UNIT = pathlib.Path('/etc/systemd/system/forge-remote-sshd.service')
MARKER = b'forge-remote-installation-v1\n'


def run(*args):
    return subprocess.run(args, check=True, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)


def require_root_owned(path):
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise RuntimeError('Unsafe installation artifact ownership or permissions')


def directory(path, mode=0o755):
    # All parents must be real, root-owned, and not writable by non-root.
    for parent in reversed(path.parents):
        if not parent.exists():
            parent.mkdir(mode=0o755)
        require_root_owned(parent)
    if path.exists() or path.is_symlink():
        require_root_owned(path)
        if not path.is_dir() or stat.S_IMODE(path.stat().st_mode) != mode:
            raise RuntimeError('Installation directory conflict')
    else:
        path.mkdir(mode=mode)
        path.chmod(mode)


def ensure_control_directory():
    if ETC.exists() or ETC.is_symlink():
        require_root_owned(ETC)
        descriptor = os.open(ETC, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            if stat.S_IMODE(os.fstat(descriptor).st_mode) == 0o700:
                os.fchmod(descriptor, 0o711)
        finally:
            os.close(descriptor)
    directory(ETC, 0o711)


def write_owned(path, content, mode):
    if path.exists() or path.is_symlink():
        require_root_owned(path)
        if not path.is_file() or path.read_bytes() != content or stat.S_IMODE(path.stat().st_mode) != mode:
            raise RuntimeError('Installation file conflict; refusing overwrite')
        return
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, 'wb', closefd=False) as output:
            output.write(content)
            output.flush()
            os.fsync(descriptor)
    finally:
        os.close(descriptor)


def install_forced_command(source):
    install_managed_helper(source, 'forced-command', {
        '28d18e70e272a7385badbdfe3e36f95abf9ecd83357a93ce5819f9237b289abe',
        '28c0cf54bcd067ad34c06bde22b5e8367ed767f88d7aa0092e5741b0647a2b3e',
        'f2531928dac31e89e9a5506711acbdcfe23d9fd18b13bd361417eeb5ee6e53f6'})


def require_stopped_supervisor_for_upgrade(source):
    target = LIB/'invitation-supervisor'
    if target.exists() or target.is_symlink():
        require_root_owned(target)
        if not target.is_file(): raise RuntimeError('Managed helper conflict')
        if target.read_bytes() != source.read_bytes():
            # systemd creates this directory before ExecStart and removes it on
            # stop. Requiring absence also rejects starting/stale/unknown state;
            # never mistake a failed systemctl query for proof of a stopped unit.
            admin = RUN/'admin'
            if admin.exists() or admin.is_symlink():
                raise RuntimeError('NOT READY: stop managed invitation supervisor before upgrade; runtime directory must be absent')


def install_invitation_supervisor(source):
    require_stopped_supervisor_for_upgrade(source)
    install_managed_helper(source, 'invitation-supervisor', {
        '69543143d332aad1afd5fca724730bffef7c39fa565f6eb1f576e5f4210e47d9'})


def install_managed_helper(source, name, known_versions):
    target = LIB/name
    content = source.read_bytes()
    if target.exists() or target.is_symlink():
        require_root_owned(target)
        if not target.is_file() or stat.S_IMODE(target.stat().st_mode) != 0o755:
            raise RuntimeError('Managed helper conflict')
        previous = target.read_bytes()
        if previous == content: return
        # Exact reviewed Stage 2/3 artifacts, never arbitrary local modifications.
        if hashlib.sha256(previous).hexdigest() not in known_versions:
            raise RuntimeError('Unknown managed helper version; refusing overwrite')
        descriptor, temporary = tempfile.mkstemp(prefix='.forced-command-', dir=LIB)
        try:
            os.fchmod(descriptor, 0o755)
            with os.fdopen(descriptor, 'wb') as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, target)
            directory_fd = os.open(LIB, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(directory_fd)
            finally: os.close(directory_fd)
        finally:
            if os.path.exists(temporary): os.unlink(temporary)
    else:
        write_owned(target, content, 0o755)


def sshd_config(host, port, liveness=True):
    address = ipaddress.ip_address(host)
    if address.is_unspecified or address.is_multicast or not 1024 <= port <= 65535:
        raise ValueError('An explicit unicast listen address and port 1024..65535 are required')
    return f'''Port {port}
ListenAddress {address}
HostKey /etc/forge-remote/host_ed25519
PidFile /run/forge-remote/sshd.pid
AuthorizedKeysFile /var/lib/forge-remote/authorized/keys
StrictModes yes
ForceCommand /usr/libexec/forge-remote/forced-command
ExposeAuthInfo yes
PubkeyAcceptedAlgorithms ssh-ed25519
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AuthenticationMethods publickey
HostbasedAuthentication no
UsePAM no
PermitRootLogin no
AllowUsers forge-ssh
PermitTTY no
DisableForwarding yes
AllowAgentForwarding no
AllowTcpForwarding no
AllowStreamLocalForwarding no
X11Forwarding no
PermitTunnel no
PermitUserEnvironment no
PermitUserRC no
MaxSessions 1
MaxAuthTries 3
LoginGraceTime 15
MaxStartups 4:50:8
LogLevel ERROR
'''+('ClientAliveInterval 5\nClientAliveCountMax 2\n' if liveness else '')


def check_user(name, shell, permitted_groups, home="/nonexistent"):
    account = pwd.getpwnam(name)
    primary = grp.getgrgid(account.pw_gid).gr_name
    groups = {entry.gr_name for entry in grp.getgrall() if name in entry.gr_mem} | {primary}
    if account.pw_uid == 0 or account.pw_dir != home or account.pw_shell != shell or not groups <= permitted_groups:
        raise RuntimeError('Managed account identity conflict')
    return account


def prepare_transport_home():
    home=STATE/'transport-home'
    directory(home,0o555)
    account=pwd.getpwnam('forge-ssh')
    if account.pw_dir!=str(home):
        # Only upgrade the known Stage 2 identity; never repurpose a foreign home.
        check_user('forge-ssh','/bin/sh',{'forge-ssh'})
        run('/usr/sbin/usermod','--home',str(home),'forge-ssh')


def prepare(host, port):
    config = sshd_config(host, port)
    source = pathlib.Path(__file__).resolve().parent / 'forced_command.py'
    require_root_owned(source)
    require_stopped_supervisor_for_upgrade(source.parent/'invitation_supervisor.py')
    for parent in source.parents:
        require_root_owned(parent)
    marker = ETC/'installation'
    if not marker.exists():
        for name in ('forge-control','forge-ssh'):
            try:
                pwd.getpwnam(name)
            except KeyError:
                continue
            raise RuntimeError('Reserved user already exists without Forge installation ownership')
    ensure_control_directory()
    write_owned(marker,MARKER,0o600)
    for name, shell in [('forge-control','/usr/sbin/nologin'),('forge-ssh','/bin/sh')]:
        try: pwd.getpwnam(name)
        except KeyError:
            run('/usr/sbin/useradd','--system','--user-group','--no-create-home','--home-dir','/nonexistent','--shell',shell,name)
            # '*' is an unusable password hash, but unlike a locked account permits SSH public-key login.
            run('/usr/sbin/usermod','--password','*',name)
    run('/usr/sbin/usermod','--append','--groups','forge-ssh','forge-control')
    control = check_user('forge-control','/usr/sbin/nologin',{'forge-control','forge-ssh'})
    directory(STATE)
    prepare_transport_home()
    peer = check_user('forge-ssh','/bin/sh',{'forge-ssh'},str(STATE/'transport-home'))
    if control.pw_uid == peer.pw_uid: raise RuntimeError('Control and transport identities must differ')
    for path in [LIB, STATE, RUN]: directory(path)
    bindings=STATE/'bindings'
    directory(bindings,0o750)
    os.chown(bindings,0,peer.pw_gid)
    authorized=STATE/'authorized'
    directory(authorized,0o750)
    os.chown(authorized,0,peer.pw_gid)
    keys=authorized/'keys'
    # Preserve dynamically installed grants on repeat setup; never replace their content.
    if keys.exists():
        require_root_owned(keys)
        if not keys.is_file() or stat.S_IMODE(keys.stat().st_mode)!=0o640:
            raise RuntimeError('Authorization source conflict')
    else: write_owned(keys,b'',0o640)
    os.chown(keys,0,peer.pw_gid)
    channel=RUN/'channel'
    if channel.exists() or channel.is_symlink():
        info=channel.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=control.pw_uid or info.st_gid!=peer.pw_gid or stat.S_IMODE(info.st_mode)!=0o750:
            raise RuntimeError('Channel directory conflict')
    else:
        channel.mkdir(mode=0o750)
        channel.chmod(0o750)
        os.chown(channel,control.pw_uid,peer.pw_gid)
    install_workloads(source.parent)
    install_forced_command(source)
    supervisor_source = source.parent/'invitation_supervisor.py'
    require_root_owned(supervisor_source)
    install_invitation_supervisor(supervisor_source)
    write_owned(pathlib.Path('/etc/systemd/system/forge-remote-invitations.service'),b'''[Unit]
Description=Forge invitation authorization supervisor
After=systemd-tmpfiles-setup.service
[Service]
Type=simple
User=root
Group=forge-control
RuntimeDirectory=forge-remote/admin
RuntimeDirectoryMode=0750
ExecStart=/usr/libexec/forge-remote/invitation-supervisor
Restart=on-failure
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_UNIX
CapabilityBoundingSet=CAP_CHOWN CAP_DAC_OVERRIDE CAP_FOWNER
ReadWritePaths=/var/lib/forge-remote/authorized /var/lib/forge-remote/bindings
[Install]
WantedBy=multi-user.target
''',0o644)
    host_key=ETC/'host_ed25519'
    ensure_host_key(host_key)
    write_sshd_config(host,port,config)
    write_owned(UNIT,b'''[Unit]
Description=Forge managed Remote Access SSH boundary
After=network.target systemd-tmpfiles-setup.service
[Service]
Type=simple
ExecStartPre=/usr/sbin/sshd -t -f /etc/forge-remote/sshd_config
ExecStart=/usr/sbin/sshd -D -e -f /etc/forge-remote/sshd_config
Restart=on-failure
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
ReadWritePaths=/run/forge-remote
[Install]
WantedBy=multi-user.target
''',0o644)
    write_owned(pathlib.Path('/etc/tmpfiles.d/forge-remote.conf'),b'''d /run/sshd 0755 root root -
d /run/forge-remote 0755 root root -
d /run/forge-remote/channel 0750 forge-control forge-ssh -
''',0o644)
    # /run/sshd is OpenSSH's pre-auth privilege-separation directory, not a grant.
    directory(pathlib.Path('/run/sshd'))
    run('/usr/sbin/sshd','-t','-f',str(ETC/'sshd_config'))



def workload_unit():
    return b"""[Unit]
Description=Forge managed workload supervisor
After=systemd-tmpfiles-setup.service
[Service]
Type=notify
NotifyAccess=main
User=root
Group=forge-control
RuntimeDirectory=forge-remote/workload-admin forge-remote/workload-peer
RuntimeDirectoryMode=0750
ExecStart=/usr/libexec/forge-remote/workload-supervisor
WatchdogSec=15s
TimeoutStopSec=20s
KillMode=control-group
Restart=on-failure
RestartSec=2s
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_UNIX
ReadWritePaths=/var/lib/forge-remote/executions
[Install]
WantedBy=multi-user.target
"""


def install_workloads(package):
    directory(STATE/'executions',0o700)
    directory(ETC/'workspaces',0o700)
    target=LIB/'workload-supervisor'
    if target.exists() and target.read_bytes()!=(package/'workload_supervisor.py').read_bytes():
        if (RUN/'workload-admin').exists():raise RuntimeError('NOT READY: stop managed workload supervisor before upgrade')
    reviewed_previous={
        'workload-supervisor': {'d05427b6b5031f8b50c0a9f97355ffceb90e0e042798d96665e70a7cb1846912'},
        'prepare-workspace': {'eb9555b2252185a32e05e2f16db56b9cbd34771fd07ecff927f1355d87a99ee7'},
    }
    for source,name in [('workload_supervisor.py','workload-supervisor'),('workload_units.py','workload_units.py'),
                        ('execution_channel.py','execution_channel.py'),('prepare_workspace.py','prepare-workspace')]:
        require_root_owned(package/source)
        install_managed_helper(package/source,name,reviewed_previous.get(name,set()))
    write_owned(pathlib.Path('/etc/systemd/system/forge-remote-workloads.service'),workload_unit(),0o644)


def write_sshd_config(host,port,config):
    path=ETC/'sshd_config'
    if path.exists() or path.is_symlink():
        require_root_owned(path)
        if path.is_file() and path.read_bytes()==sshd_config(host,port,False).encode():
            descriptor,temporary=tempfile.mkstemp(prefix='.sshd-',dir=ETC)
            try:
                os.fchmod(descriptor,0o600)
                with os.fdopen(descriptor,'wb') as output:output.write(config.encode());output.flush();os.fsync(output.fileno())
                os.replace(temporary,path)
            finally:
                if os.path.exists(temporary):os.unlink(temporary)
    write_owned(path,config.encode(),0o600)

def ensure_host_key(host_key):
    public=pathlib.Path(str(host_key)+'.pub')
    if host_key.is_symlink() or public.is_symlink():
        raise RuntimeError('Host key conflict')
    if not host_key.exists():
        if host_key.is_symlink() or pathlib.Path(str(host_key)+'.pub').exists(): raise RuntimeError('Host key conflict')
        run('/usr/bin/ssh-keygen','-q','-t','ed25519','-N','','-f',str(host_key))
    require_root_owned(host_key)
    if not host_key.is_file(): raise RuntimeError('Host key must be a regular file')
    if stat.S_IMODE(host_key.stat().st_mode)!=0o600: raise RuntimeError('Host private key permissions must be 0600')
    if not public.is_file(): raise RuntimeError('Host public key conflict')
    require_root_owned(public)
    actual=run('/usr/bin/ssh-keygen','-y','-f',str(host_key)).stdout.decode('ascii').split()
    if public.read_text().split()[:2]!=actual[:2]: raise RuntimeError('Host public key does not match private identity')


def require_available_endpoint(host, port):
    address=ipaddress.ip_address(host)
    with socket.socket(socket.AF_INET6 if address.version==6 else socket.AF_INET,socket.SOCK_STREAM) as probe:
        try: probe.bind((str(address),port))
        except OSError as failure:
            raise RuntimeError('NOT READY: configured listen address or port unavailable; stop managed sshd before reinstall') from failure


def install(host, port):
    if os.geteuid()!=0: raise RuntimeError('Privileged setup requires root')
    if platform.system()!='Linux': raise RuntimeError('NOT READY: Linux/systemd required')
    for binary in ['/usr/bin/python3','/usr/sbin/sshd','/usr/bin/ssh-keygen','/usr/sbin/useradd','/usr/sbin/usermod','/usr/bin/systemctl']:
        if not pathlib.Path(binary).is_file(): raise RuntimeError('NOT READY: missing installation prerequisite')
    sshd_config(host,port)
    require_available_endpoint(host,port)
    prepare(host,port)
    if not pathlib.Path('/run/systemd/system').is_dir():
        raise RuntimeError('NOT READY: artifacts prepared; system systemd manager unavailable')
    run('/usr/bin/systemctl','daemon-reload')
    # No service start, authorization entry, or grant is created by installation.
    print('INSTALLED: managed SSH artifacts; no access granted')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--listen-address',required=True)
    parser.add_argument('--port',type=int,default=2222)
    args=parser.parse_args()
    try: install(args.listen_address,args.port)
    except RuntimeError as failure:
        print(str(failure),file=sys.stderr)
        sys.exit(1)
    except (OSError,ValueError,subprocess.SubprocessError):
        # Paths/commands/internal exceptions can include local details. Keep CLI failures bounded and safe.
        print('NOT READY: installation failed; check privileges, prerequisites and owned artifact conflicts',file=sys.stderr)
        sys.exit(1)
