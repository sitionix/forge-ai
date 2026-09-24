#!/usr/bin/python3 -I
"""Narrow privileged installation. Never alters personal or host sshd configuration."""
import argparse
import grp
import hashlib
import tempfile
import os
import pathlib
import platform
import pwd
import shutil
import stat
import subprocess
import sys

ETC = pathlib.Path('/etc/forge-remote')
LIB = pathlib.Path('/usr/libexec/forge-remote')
STATE = pathlib.Path('/var/lib/forge-remote')
RUN = pathlib.Path('/run/forge-remote')
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


def write_invitation_unit(content, path=pathlib.Path('/etc/systemd/system/forge-remote-invitations.service'),
                          known_hashes=frozenset({'9a5e18c00730a611b440fa6549b7c21fdd7bd06f61bcaa0c2eb86b9f242dc9be'})):
    if path.exists() or path.is_symlink():
        require_root_owned(path)
        if not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o644:
            raise RuntimeError('Installation file conflict; refusing overwrite')
        previous = path.read_bytes()
        if previous != content:
            if hashlib.sha256(previous).hexdigest() not in known_hashes:
                raise RuntimeError('Unknown invitation unit version; refusing overwrite')
            descriptor, temporary = tempfile.mkstemp(prefix='.forge-remote-invitations-', dir=path.parent)
            try:
                os.fchmod(descriptor, 0o644)
                with os.fdopen(descriptor, 'wb') as output:
                    output.write(content)
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary): os.unlink(temporary)
            return
    write_owned(path, content, 0o644)


def install_forced_command(source):
    install_managed_helper(source, 'forced-command', {
        '28d18e70e272a7385badbdfe3e36f95abf9ecd83357a93ce5819f9237b289abe',
        '28c0cf54bcd067ad34c06bde22b5e8367ed767f88d7aa0092e5741b0647a2b3e',
        'f2531928dac31e89e9a5506711acbdcfe23d9fd18b13bd361417eeb5ee6e53f6',
        'e86e508e186386169200c5307e556746838658ce07b83a954f97fdcb676aa316'})


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
        '69543143d332aad1afd5fca724730bffef7c39fa565f6eb1f576e5f4210e47d9',
        'ba67c74b441c637e319b4c68f99937d987c3668dd241145848599563386f4b5d'})


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


def prepare():
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
    legacy_keys=STATE/'authorized'/'keys'
    if legacy_keys.exists() or legacy_keys.is_symlink():
        require_root_owned(legacy_keys)
        if not legacy_keys.is_file() or legacy_keys.read_bytes():
            raise RuntimeError('NOT READY: drain old managed SSH grants before switching to system SSH')
    ssh_home=STATE/'transport-home'/'.ssh'
    directory(ssh_home,0o755)
    keys=ssh_home/'authorized_keys'
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
    write_invitation_unit(b'''[Unit]
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
ReadWritePaths=/var/lib/forge-remote/transport-home/.ssh /var/lib/forge-remote/bindings
[Install]
WantedBy=multi-user.target
''')
    host_key=pathlib.Path('/etc/ssh/ssh_host_ed25519_key')
    ensure_host_key(host_key)
    write_owned(pathlib.Path('/etc/tmpfiles.d/forge-remote.conf'),b'''d /run/sshd 0755 root root -
d /run/forge-remote 0755 root root -
d /run/forge-remote/channel 0750 forge-control forge-ssh -
''',0o644)
    # /run/sshd is OpenSSH's pre-auth privilege-separation directory, not a grant.
    directory(pathlib.Path('/run/sshd'))
    run('/usr/sbin/sshd','-t')



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


def install():
    if os.geteuid()!=0: raise RuntimeError('Privileged setup requires root')
    if platform.system()!='Linux': raise RuntimeError('NOT READY: Linux/systemd required')
    for binary in ['/usr/bin/python3','/usr/sbin/sshd','/usr/bin/ssh-keygen','/usr/sbin/useradd','/usr/sbin/usermod','/usr/bin/systemctl']:
        if not pathlib.Path(binary).is_file(): raise RuntimeError('NOT READY: missing installation prerequisite')
    prepare()
    if not pathlib.Path('/run/systemd/system').is_dir():
        raise RuntimeError('NOT READY: artifacts prepared; system systemd manager unavailable')
    run('/usr/bin/systemctl','daemon-reload')
    # No service start, authorization entry, or grant is created by installation.
    print('INSTALLED: system SSH account artifacts; no access granted')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try: install()
    except RuntimeError as failure:
        print(str(failure),file=sys.stderr)
        sys.exit(1)
    except (OSError,ValueError,subprocess.SubprocessError):
        # Paths/commands/internal exceptions can include local details. Keep CLI failures bounded and safe.
        print('NOT READY: installation failed; check privileges, prerequisites and owned artifact conflicts',file=sys.stderr)
        sys.exit(1)
