#!/usr/bin/env python3
"""Prepare protected ACCESSOR-local execution socket and Agent environment."""
import argparse
import grp
import os
import pathlib
import pwd
import re
import stat


def managed_file(path, content, mode, uid, gid):
    if path.is_symlink():
        raise RuntimeError('Refusing symlink local execution artifact')
    if path.exists():
        info = path.stat()
        if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != mode
                or info.st_uid != uid or info.st_gid != gid or path.read_text() != content):
            raise RuntimeError('Existing local execution artifact differs')
        return
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(descriptor, 'w') as stream:
        os.fchmod(stream.fileno(), mode)
        os.fchown(stream.fileno(), uid, gid)
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())


def prepare(runtime, management, tmpfiles, operator_user, agent_uid, agent_gid, operator_uid, operator_gid):
    if (operator_user in ('root', 'forge-control', 'forge-ssh') or
            not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', operator_user) or operator_uid == 0):
        raise ValueError('A distinct unprivileged local Codex user is required')
    for path in (runtime, management, tmpfiles):
        if not path.is_absolute() or any(parent.is_symlink() for parent in (path, *path.parents)):
            raise RuntimeError('Protected absolute paths without symlinks required')
    parent = runtime.parent
    if not parent.is_dir() or parent.stat().st_uid != os.geteuid() or parent.stat().st_mode & 0o022:
        raise RuntimeError('Protected runtime parent required')
    if (not management.is_dir() or management.stat().st_uid != os.geteuid()
            or stat.S_IMODE(management.stat().st_mode) != 0o711):
        raise RuntimeError('Prepared management directory required')
    if not tmpfiles.parent.is_dir() or tmpfiles.parent.stat().st_uid != os.geteuid() or tmpfiles.parent.stat().st_mode & 0o022:
        raise RuntimeError('Protected tmpfiles directory required')
    operator_group = grp.getgrgid(operator_gid).gr_name
    if runtime.exists():
        info = runtime.stat()
        if (not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o2750
                or info.st_uid != agent_uid or info.st_gid != operator_gid):
            raise RuntimeError('Existing local execution directory differs')
    else:
        runtime.mkdir(mode=0o2750)
        os.chown(runtime, agent_uid, operator_gid)
        runtime.chmod(0o2750)
    environment = ('FORGE_AGENT_REMOTE_ACCESS_LOCAL_EXEC_ENABLED=true\n'
                   f'FORGE_AGENT_REMOTE_ACCESS_LOCAL_EXEC_OPERATOR_USER={operator_user}\n')
    managed_file(management/'local-exec-agent.env', environment, 0o600, agent_uid, agent_gid)
    managed_file(tmpfiles,
                 f'd {runtime} 2750 forge-control {operator_group} -\n',
                 0o644, os.geteuid(), os.getegid())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operator-user', required=True, help='The local identity running Codex')
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Local execution setup requires root; Agent itself remains unprivileged')
    control = pwd.getpwnam('forge-control')
    operator = pwd.getpwnam(args.operator_user)
    if operator.pw_uid == control.pw_uid:
        parser.error('Codex operator must have a distinct UID from Forge Agent')
    prepare(pathlib.Path('/run/forge-remote/local-exec'),
            pathlib.Path('/etc/forge-remote/management'),
            pathlib.Path('/etc/tmpfiles.d/forge-remote-local-exec.conf'),
            args.operator_user, control.pw_uid, control.pw_gid,
            operator.pw_uid, operator.pw_gid)
    print('Prepared local execution configuration; attach EnvironmentFile and install reviewed helper before use.')


if __name__ == '__main__':
    main()
