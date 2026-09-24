#!/usr/bin/env python3
"""Prepare protected Stage 6 credentials/env files; never start/reconfigure services."""
import argparse
import ipaddress
import os
import pathlib
import pwd
import re
import secrets
import stat
import urllib.parse


def protected_file(path, value, uid, gid):
    if path.is_symlink():
        raise RuntimeError('Refusing symlink management artifact')
    if path.exists():
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o777 != 0o600 or info.st_uid != uid or path.read_text() != value:
            raise RuntimeError('Existing management artifact differs; operator inspection required')
        return
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        os.fchmod(stream.fileno(), 0o600)
        os.fchown(stream.fileno(), uid, gid)
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def existing_secret(path, uid):
    if path.is_symlink():
        raise RuntimeError('Refusing symlink credential')
    if not path.exists():
        return None
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o777 != 0o600 or info.st_uid != uid or info.st_size > 256:
        raise RuntimeError('Unsafe existing credential')
    value = path.read_text().strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]{43,128}', value):
        raise RuntimeError('Invalid existing credential')
    return value


def prepare(root, agent_uid, agent_gid, operator_uid, operator_gid, origin):
    parsed = urllib.parse.urlsplit(origin)
    if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment or not parsed.port:
        raise ValueError('Explicit loopback origin required')
    if not ipaddress.ip_address(parsed.hostname).is_loopback:
        raise ValueError('Loopback origin required')
    root = root.absolute()
    for parent in (root, *root.parents):
        if parent.is_symlink():
            raise RuntimeError('Refusing symlink management directory')
        if parent.exists():
            mode=parent.stat().st_mode
            if mode & 0o022 and not mode & stat.S_ISVTX:
                raise RuntimeError('Writable management ancestor')
    if not root.exists():
        root.mkdir(mode=0o711, parents=False)
        root.chmod(0o711)  # mkdir mode is filtered by umask; both service UIDs must traverse.
    if not root.is_dir() or root.stat().st_mode & 0o777 != 0o711:
        raise RuntimeError('Management directory must have explicit mode 0711')
    agent_file, nexus_file, operator_file = (root/name for name in ('agent-service.secret', 'nexus-service.secret', 'operator.secret'))
    old_agent, old_nexus = existing_secret(agent_file, agent_uid), existing_secret(nexus_file, operator_uid)
    if old_agent and old_nexus and old_agent != old_nexus:
        raise RuntimeError('Service credential copies differ; refusing rotation')
    service = old_agent or old_nexus or secrets.token_urlsafe(32)
    operator = existing_secret(operator_file, operator_uid) or secrets.token_urlsafe(32)
    if service == operator:
        raise RuntimeError('Operator and service credentials must differ')
    protected_file(agent_file, service+'\n', agent_uid, agent_gid)
    protected_file(nexus_file, service+'\n', operator_uid, operator_gid)
    protected_file(operator_file, operator+'\n', operator_uid, operator_gid)
    if any(character in str(root) for character in '\n\r"\\ '):
        raise ValueError('Management directory must have a simple absolute path')
    protected_file(root/'agent.env', 'FORGE_AGENT_HOST=127.0.0.1\nFORGE_AGENT_REMOTE_ACCESS_MANAGEMENT_ENABLED=true\n'
                   f'FORGE_AGENT_REMOTE_ACCESS_SERVICE_SECRET_FILE={agent_file}\n', agent_uid, agent_gid)
    protected_file(root/'nexus.env', f'FORGE_NEXUS_HOST={parsed.hostname}\nFORGE_REMOTE_ACCESS_ENABLED=true\n'
                   f'FORGE_REMOTE_ACCESS_SERVICE_SECRET_FILE={nexus_file}\nFORGE_REMOTE_ACCESS_OPERATOR_SECRET_FILE={operator_file}\n'
                   f'FORGE_REMOTE_ACCESS_OPERATOR_ORIGIN={origin}\nFORGE_REMOTE_ACCESS_AGENT_READ_TIMEOUT=120s\n', operator_uid, operator_gid)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=pathlib.Path, required=True)
    parser.add_argument('--agent-user', default='forge-control')
    parser.add_argument('--operator-user', required=True, help='Local operator identity running Nexus')
    parser.add_argument('--origin', required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Protected management setup requires root; Agent and Nexus remain unprivileged')
    agent, operator = pwd.getpwnam(args.agent_user), pwd.getpwnam(args.operator_user)
    prepare(args.directory, agent.pw_uid, agent.pw_gid, operator.pw_uid, operator.pw_gid, args.origin)
    print('Prepared protected Agent/Nexus EnvironmentFiles. No services changed or started.')


if __name__ == '__main__':
    main()
