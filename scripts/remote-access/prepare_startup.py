#!/usr/bin/python3 -I
"""Protected Remote Access startup preparation; no grants are created here."""

import ipaddress
import argparse
import json
import os
import platform
import subprocess
import urllib.parse
import shlex
import stat
import sys
from pathlib import Path


SSHD = Path('/usr/sbin/sshd')


def sshd_available():
    return SSHD.is_file() and os.access(SSHD, os.X_OK)


def is_debian_host():
    release = Path('/etc/os-release').read_text()
    return platform.system() == 'Linux' and any(
        line in release.splitlines() for line in ('ID=ubuntu', 'ID=debian'))


def ensure_openssh_server():
    """Install the missing host binary without exposing the distribution SSH listener."""
    if sshd_available():
        return
    if not is_debian_host() or not Path('/usr/bin/apt-get').is_file():
        raise RuntimeError('REMOTE_ACCESS_SSHD_NOT_READY')
    units = ('ssh.service', 'ssh.socket')
    for unit in units:
        state = subprocess.run(['/usr/bin/systemctl', 'show', unit, '--property=LoadState', '--value'],
                               stdin=subprocess.DEVNULL, capture_output=True, text=True,
                               check=True, timeout=10)
        if state.stdout.strip() != 'not-found':
            raise RuntimeError('REMOTE_ACCESS_SSHD_CONFLICT')
    subprocess.run(['/usr/bin/systemctl', 'mask', *units], stdin=subprocess.DEVNULL,
                   capture_output=True, check=True, timeout=20)
    environment = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'DEBIAN_FRONTEND': 'noninteractive'}
    try:
        subprocess.run(['/usr/bin/apt-get', 'update'], env=environment,
                       stdin=subprocess.DEVNULL, capture_output=True, check=True, timeout=600)
        subprocess.run(['/usr/bin/apt-get', 'install', '-y', '--no-install-recommends', 'openssh-server'],
                       env=environment, stdin=subprocess.DEVNULL, capture_output=True,
                       check=True, timeout=900)
        if not sshd_available():
            raise RuntimeError('REMOTE_ACCESS_SSHD_NOT_READY')
        subprocess.run(['/usr/bin/systemctl', 'unmask', *units], stdin=subprocess.DEVNULL,
                       capture_output=True, check=True, timeout=20)
        subprocess.run(['/usr/bin/systemctl', 'disable', '--now', *units], stdin=subprocess.DEVNULL,
                       capture_output=True, check=True, timeout=20)
        for unit in units:
            active = subprocess.run(['/usr/bin/systemctl', 'is-active', '--quiet', unit],
                                    stdin=subprocess.DEVNULL, capture_output=True,
                                    check=False, timeout=10)
            if active.returncode == 0:
                raise RuntimeError('REMOTE_ACCESS_SSHD_CONFLICT')
    except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
        # A failed or partial package installation must never expose default SSH.
        subprocess.run(['/usr/bin/systemctl', 'mask', '--now', *units],
                       stdin=subprocess.DEVNULL, capture_output=True, check=False, timeout=20)
        raise RuntimeError('REMOTE_ACCESS_SSHD_NOT_READY') from failure


def select_address(routes, interfaces, requested):
    """Choose a local default-route source, never a wildcard or arbitrary interface."""
    local = {
        (interface.get('ifname'), entry.get('local'))
        for interface in interfaces
        for entry in interface.get('addr_info', ())
        if entry.get('family') in ('inet', 'inet6')
    }

    def usable(value):
        try:
            address = ipaddress.ip_address(value)
        except (ValueError, TypeError):
            return False
        return not (address.is_unspecified or address.is_loopback or
                    address.is_link_local or address.is_multicast)

    if requested is not None:
        if not usable(requested) or not any(address == requested for _, address in local):
            raise ValueError('REMOTE_ACCESS_ADDRESS_REQUIRED')
        return requested

    candidates = set()
    for route in routes:
        if route.get('dst') != 'default':
            continue
        device = route.get('dev')
        source = route.get('prefsrc')
        if source:
            if (device, source) in local and usable(source):
                candidates.add(source)
        else:
            candidates.update(address for interface, address in local
                              if interface == device and usable(address))
    if len(candidates) != 1:
        raise ValueError('REMOTE_ACCESS_ADDRESS_REQUIRED')
    return candidates.pop()


def ensure_database(jdbc_url, username, password):
    """Create only the dedicated database using a fixed SQL identifier."""
    if not jdbc_url.startswith('jdbc:'):
        raise ValueError('REMOTE_ACCESS_DB_URL_INVALID')
    parsed = urllib.parse.urlsplit(jdbc_url[5:])
    if (parsed.scheme != 'postgresql' or not parsed.hostname or
            parsed.path != '/forge_remote_access' or parsed.username or
            parsed.password or parsed.query or parsed.fragment):
        raise ValueError('REMOTE_ACCESS_DB_URL_INVALID')
    try:
        port = parsed.port or 5432
    except ValueError as error:
        raise ValueError('REMOTE_ACCESS_DB_URL_INVALID') from error
    connection = ['/usr/bin/psql', '-X', '-v', 'ON_ERROR_STOP=1',
                  '-h', parsed.hostname, '-p', str(port), '-U', username,
                  '-d', 'postgres', '-At', '-c']
    environment = {'PATH': '/usr/bin:/bin', 'PGPASSWORD': password,
                   'PGCONNECT_TIMEOUT': '5', 'PGPASSFILE': '/dev/null'}
    exists = subprocess.run(connection + [
        "SELECT 1 FROM pg_database WHERE datname = 'forge_remote_access'"],
        env=environment, stdin=subprocess.DEVNULL, capture_output=True,
        text=True, check=True, timeout=15)
    if exists.stdout.strip() == '1':
        return
    if exists.stdout.strip():
        raise RuntimeError('REMOTE_ACCESS_DB_INSPECTION_FAILED')
    subprocess.run(connection + ['CREATE DATABASE forge_remote_access'],
                   env=environment, stdin=subprocess.DEVNULL,
                   capture_output=True, text=True, check=True, timeout=20)


def read_control_env(path):
    """Read only the fixed database settings written by Forge's unit renderer."""
    if path.is_symlink():
        raise ValueError('REMOTE_ACCESS_ENV_INVALID')
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError('REMOTE_ACCESS_ENV_INVALID')
    allowed = {'FORGE_AGENT_DB_URL', 'FORGE_AGENT_DB_USERNAME',
               'FORGE_AGENT_DB_PASSWORD', 'FORGE_AGENT_HOST', 'FORGE_AGENT_PORT',
               'FORGE_AGENT_REMOTE_ACCESS_CHANNEL_ENABLED'}
    values = {}
    for line in path.read_text().splitlines():
        try:
            parts = shlex.split(line)
        except ValueError as error:
            raise ValueError('REMOTE_ACCESS_ENV_INVALID') from error
        if len(parts) != 1 or '=' not in parts[0]:
            raise ValueError('REMOTE_ACCESS_ENV_INVALID')
        key, value = parts[0].split('=', 1)
        if key not in allowed or key in values or not value:
            raise ValueError('REMOTE_ACCESS_ENV_INVALID')
        values[key] = value
    if not {'FORGE_AGENT_DB_URL', 'FORGE_AGENT_DB_USERNAME',
            'FORGE_AGENT_DB_PASSWORD'} <= values.keys():
        raise ValueError('REMOTE_ACCESS_ENV_INVALID')
    return values


def write_endpoint_env(path, address, expected_uid=0):
    """Persist the selected endpoint without silently changing an issued token's host."""
    parsed = ipaddress.ip_address(address)
    if parsed.is_unspecified or parsed.is_loopback or parsed.is_link_local or parsed.is_multicast:
        raise ValueError('REMOTE_ACCESS_ADDRESS_REQUIRED')
    value = str(parsed)
    content = ('FORGE_AGENT_REMOTE_ACCESS_ADVERTISED_HOST=' + value + '\n').encode('ascii')
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != expected_uid or
                stat.S_IMODE(info.st_mode) != 0o600 or path.read_bytes() != content):
            raise RuntimeError('REMOTE_ACCESS_ENDPOINT_CONFLICT')
        return
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def managed_sshd_running():
    state = subprocess.run(['/usr/bin/systemctl', 'show', 'forge-remote-sshd.service',
                            '--property=LoadState', '--property=ActiveState'],
                           stdin=subprocess.DEVNULL, capture_output=True,
                           text=True, check=False, timeout=10)
    if state.returncode != 0:
        raise RuntimeError('REMOTE_ACCESS_SYSTEMD_UNAVAILABLE')
    values = dict(line.split('=', 1) for line in state.stdout.splitlines()
                  if line.count('=') == 1)
    if values.get('LoadState') == 'not-found' and values.get('ActiveState') == 'inactive':
        return False
    if values.get('LoadState') != 'loaded':
        raise RuntimeError('REMOTE_ACCESS_SYSTEMD_UNAVAILABLE')
    if values.get('ActiveState') == 'active':
        return True
    if values.get('ActiveState') in ('inactive', 'failed'):
        return False
    raise RuntimeError('REMOTE_ACCESS_SYSTEMD_UNAVAILABLE')


def installed_transport_matches(package, listen_address):
    config = Path('/etc/forge-remote/sshd_config')
    if not config.is_file() or config.is_symlink():
        return False
    lines = config.read_text().splitlines()
    if lines.count('ListenAddress ' + listen_address) != 1 or lines.count('Port 2222') != 1:
        return False
    installed = Path('/usr/libexec/forge-remote')
    for source, target in (
        ('forced_command.py', 'forced-command'),
        ('invitation_supervisor.py', 'invitation-supervisor'),
        ('workload_supervisor.py', 'workload-supervisor'),
        ('workload_units.py', 'workload_units.py'),
        ('execution_channel.py', 'execution_channel.py'),
        ('prepare_workspace.py', 'prepare-workspace'),
    ):
        current = installed / target
        if (not current.is_file() or current.is_symlink() or
                current.read_bytes() != (package / source).read_bytes()):
            return False
    return True


def prepare_services(package, operator_user, listen_address, jdbc_url, db_user, db_password):
    """Run the reviewed root-only setup scripts from a protected package."""
    active = managed_sshd_running()
    if active and not installed_transport_matches(package, listen_address):
        raise RuntimeError('REMOTE_ACCESS_UPGRADE_REQUIRES_DRAIN')
    ensure_database(jdbc_url, db_user, db_password)
    if not active:
        ensure_openssh_server()
    commands = [
        ('prepare_management.py', '--directory', '/etc/forge-remote/management',
         '--agent-user', 'forge-control', '--operator-user', operator_user,
         '--origin', 'http://127.0.0.1:9100'),
        ('prepare_local_exec.py', '--operator-user', operator_user),
    ]
    if not active:
        commands.insert(0, ('install.py', '--listen-address', listen_address, '--port', '2222'))
    for name, *args in commands:
        subprocess.run(['/usr/bin/python3', '-I', str(package / name), *args],
                       stdin=subprocess.DEVNULL, capture_output=True,
                       text=True, check=True, timeout=90)


def require_protected_package(package):
    for path in (package, *package.parents):
        info = path.lstat()
        if info.st_uid != 0 or info.st_mode & 0o022 or stat.S_ISLNK(info.st_mode):
            raise RuntimeError('REMOTE_ACCESS_PACKAGE_CONFLICT')
    for name in ('install.py', 'prepare_management.py', 'prepare_local_exec.py',
                 'prepare_rootfs.py', 'rootfs.Dockerfile'):
        source = package / name
        info = source.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise RuntimeError('REMOTE_ACCESS_PACKAGE_CONFLICT')


def ip_state(*args):
    result = subprocess.run(['/usr/sbin/ip', '-j', *args],
                            stdin=subprocess.DEVNULL, capture_output=True,
                            text=True, check=True, timeout=10)
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operator-user', required=True)
    parser.add_argument('--listen-address')
    args = parser.parse_args()
    if os.geteuid() != 0:
        print('REMOTE_ACCESS_REQUIRES_ROOT', file=sys.stderr)
        return 1
    try:
        package = Path(__file__).resolve().parent
        require_protected_package(package)
        control_path = Path('/etc/forge-ai/forge-remote-agent.env')
        if control_path.stat().st_uid != 0:
            raise RuntimeError('REMOTE_ACCESS_ENV_INVALID')
        environment = read_control_env(control_path)
        routes = ip_state('route', 'show', 'default')
        addresses = ip_state('address', 'show')
        try:
            address = select_address(routes, addresses, args.listen_address)
        except ValueError:
            if not sys.stdin.isatty() or args.listen_address is not None:
                raise
            print('Select this machine\'s reachable LAN IP address:', file=sys.stderr)
            requested = input('LAN IP: ').strip()
            address = select_address(routes, addresses, requested)
        prepare_services(package, args.operator_user, address,
                         environment['FORGE_AGENT_DB_URL'],
                         environment['FORGE_AGENT_DB_USERNAME'],
                         environment['FORGE_AGENT_DB_PASSWORD'])
        write_endpoint_env(Path('/etc/forge-ai/forge-remote-endpoint.env'), address)
        subprocess.run(['/usr/bin/python3', '-I', str(package / 'prepare_rootfs.py')],
                       stdin=subprocess.DEVNULL, capture_output=True,
                       text=True, check=True, timeout=1200)
        print('REMOTE_ACCESS_PREPARED')
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyError) as error:
        code = str(error)
        if code not in {'REMOTE_ACCESS_ADDRESS_REQUIRED', 'REMOTE_ACCESS_ROOTFS_NOT_READY',
                        'REMOTE_ACCESS_UPGRADE_REQUIRES_DRAIN', 'REMOTE_ACCESS_PACKAGE_CONFLICT',
                        'REMOTE_ACCESS_ENV_INVALID', 'REMOTE_ACCESS_DB_URL_INVALID',
                        'REMOTE_ACCESS_ENDPOINT_CONFLICT', 'REMOTE_ACCESS_SSHD_NOT_READY',
                        'REMOTE_ACCESS_SSHD_CONFLICT'}:
            code = 'REMOTE_ACCESS_SETUP_FAILED'
        print(code, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
