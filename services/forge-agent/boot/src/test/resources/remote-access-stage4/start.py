"""Disposable integration environment. All authorization decisions are production Java services."""
import importlib.util
import os
import pathlib
import pwd
import subprocess
import time

package = pathlib.Path('/opt/forge-remote-package')
os.chown(package, 0, 0)
package.chmod(0o755)
for source in package.rglob('*'):
    os.chown(source, 0, 0)
    if source.is_file():
        source.chmod(0o644)
    elif source.is_dir():
        source.chmod(0o755)
spec = importlib.util.spec_from_file_location('installer', package/'install.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
installer.prepare()
subprocess.run(['systemd-tmpfiles', '--create', '/etc/tmpfiles.d/forge-remote.conf'], check=True)
control = pwd.getpwnam('forge-control')
# The disposable container has no systemd PID 1; reproduce the unit's RuntimeDirectory contract.
admin = pathlib.Path('/run/forge-remote/admin')
admin.mkdir(mode=0o750)
os.chown(admin, 0, control.pw_gid)
state = pathlib.Path('/fixture/state')
state.mkdir(mode=0o700)
os.chown(state, control.pw_uid, control.pw_gid)
supervisor = subprocess.Popen(['/usr/bin/python3', '-I', '/usr/libexec/forge-remote/invitation-supervisor'])
sshd = subprocess.Popen(['/usr/sbin/sshd', '-D', '-e', '-p', '22222'])
try:
    for _ in range(100):
        if supervisor.poll() is not None or sshd.poll() is not None:
            raise RuntimeError('Production SSH boundary stopped')
        if pathlib.Path('/run/forge-remote/admin/invitations.sock').exists():
            print('STAGE4_SSH_READY', flush=True)
            break
        time.sleep(.05)
    else:
        raise RuntimeError('Supervisor did not become ready')
    while supervisor.poll() is None and sshd.poll() is None:
        time.sleep(.2)
finally:
    supervisor.terminate()
    sshd.terminate()
