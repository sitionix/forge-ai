#!/usr/bin/python3 -I
"""Stage pinned Remote Access bytes only when the local operator enables access."""

import hashlib
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request


PACKAGE_FILES = (
    'install.py', 'prepare_startup.py', 'prepare_management.py',
    'prepare_local_exec.py', 'prepare_rootfs.py', 'rootfs.Dockerfile',
    'forced_command.py', 'invitation_supervisor.py', 'workload_supervisor.py',
    'workload_units.py', 'execution_channel.py', 'prepare_workspace.py',
    'forge-remote',
)
AGENT_JAR = pathlib.Path('services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar')
MANIFEST = pathlib.Path('/etc/forge-ai/forge-remote-enable.json')
PACKAGE = pathlib.Path('/usr/local/lib/forge-remote-setup')
INSTALLED_JAR = pathlib.Path('/usr/local/lib/forge-remote/forge-agent.jar')


def read_regular(path, limit):
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as failure:
        raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED') from failure
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
        with os.fdopen(os.dup(descriptor), 'rb') as source:
            content = source.read(limit + 1)
        if len(content) > limit:
            raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
        return content
    finally:
        os.close(descriptor)


def sources(root, agent_jar):
    return {name: root / 'scripts/remote-access' / name for name in PACKAGE_FILES} | {'agent.jar': agent_jar}


def build_manifest(root, agent_jar=None):
    root = pathlib.Path(root).absolute()
    if not root.is_dir():
        raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
    agent_jar = pathlib.Path(agent_jar).absolute() if agent_jar else root / AGENT_JAR
    return {'version': 1, 'sourceRoot': str(root), 'agentJarPath': str(agent_jar), 'sha256': {
        name: hashlib.sha256(read_regular(path, 200_000_000 if name == 'agent.jar' else 1_000_000)).hexdigest()
        for name, path in sources(root, agent_jar).items()
    }}


def write_pinned(target, content, mode):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink() or target.parent.is_symlink():
        raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
    descriptor, temporary = tempfile.mkstemp(prefix='.forge-', dir=target.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            os.fchmod(output.fileno(), mode)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def stage(manifest, package, jar):
    if manifest.get('version') != 1 or set(manifest.get('sha256', {})) != set(PACKAGE_FILES) | {'agent.jar'}:
        raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
    root = pathlib.Path(manifest['sourceRoot'])
    agent_jar = pathlib.Path(manifest['agentJarPath'])
    if not root.is_absolute() or not agent_jar.is_absolute():
        raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
    verified = {}
    for name, path in sources(root, agent_jar).items():
        content = read_regular(path, 200_000_000 if name == 'agent.jar' else 1_000_000)
        if hashlib.sha256(content).hexdigest() != manifest['sha256'][name]:
            raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
        verified[name] = content
    for name in PACKAGE_FILES:
        write_pinned(package / name, verified[name], 0o644 if name == 'rootfs.Dockerfile' else 0o755)
    write_pinned(jar, verified['agent.jar'], 0o644)


def wait_healthy(url):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200 and b'"UP"' in response.read(256):
                    return True
        except (OSError, ValueError):
            pass
        time.sleep(1)
    return False


def run_fixed(command):
    subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True,
                   check=True, timeout=1800 if command[0] == '/usr/bin/python3' else 90)


def enable_runtime(manifest, package, jar, operator_user, run=run_fixed, healthy=wait_healthy):
    stage(manifest, package, jar)
    run(['/usr/bin/python3', '-I', str(package / 'prepare_startup.py'),
         '--operator-user', operator_user])
    units = ('forge-remote-agent.service', 'forge-remote-nexus.service')
    try:
        # The host SSH service is shared with other users; never stop it as rollback.
        run(['/usr/bin/systemctl', 'start', 'ssh.service'])
        for unit in units:
            run(['/usr/bin/systemctl', 'start', unit])
        for url in ('http://127.0.0.1:7092/actuator/health',
                    'http://127.0.0.1:9100/fgaisox/actuator/health'):
            if not healthy(url):
                raise RuntimeError('REMOTE_ACCESS_START_FAILED')
    except (OSError, RuntimeError, subprocess.SubprocessError):
        for unit in reversed(units):
            try:
                run(['/usr/bin/systemctl', 'stop', unit])
            except (OSError, RuntimeError, subprocess.SubprocessError):
                pass
        raise


def main():
    if len(sys.argv) == 5 and sys.argv[1] == 'manifest':
        manifest = build_manifest(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[4]))
        pathlib.Path(sys.argv[3]).write_text(json.dumps(manifest, sort_keys=True))
        return 0
    if len(sys.argv) != 3 or sys.argv[1] != 'run' or os.geteuid() != 0:
        return 1
    info = MANIFEST.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
        raise RuntimeError('REMOTE_ACCESS_PACKAGE_CHANGED')
    enable_runtime(json.loads(MANIFEST.read_text()), PACKAGE, INSTALLED_JAR, sys.argv[2])
    print('REMOTE_ACCESS_PREPARED')
    return 0


if __name__ == '__main__':
    sys.exit(main())
