#!/usr/bin/python3 -I
"""Normal systemd installation of the existing MCP security/runtime prerequisites."""
import argparse
import grp
import importlib.util
import json
import os
import pathlib
import pwd
import shutil
import shlex
import stat
import subprocess
import tempfile
import uuid


def trusted_parent(path, trusted_uid=0):
    if not path.is_absolute() or path != pathlib.Path(os.path.normpath(path)):
        raise RuntimeError('Canonical absolute installation path required')
    for parent in reversed(path.parents):
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, trusted_uid) or info.st_mode & 0o022:
            raise RuntimeError('Unsafe installation ancestor')


def protected_directory(path, uid, gid, mode):
    trusted_parent(path, trusted_uid=uid)
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != uid or info.st_gid != gid or stat.S_IMODE(info.st_mode) != mode:
            raise RuntimeError('Unsafe existing runtime directory')
        return
    path.mkdir(mode=mode)
    path.chmod(mode)
    os.chown(path, uid, gid)


def install_file(source, target, mode):
    trusted_parent(target)
    if target.is_symlink() or (target.exists() and (not target.is_file() or target.stat().st_nlink != 1)):
        raise RuntimeError('Unsafe existing runtime artifact')
    fd, staging = tempfile.mkstemp(dir=target.parent, prefix='.install-')
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(source.read_bytes())
            os.fchmod(stream.fileno(), mode)
            os.fchown(stream.fileno(), 0, 0)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(staging, target)
    finally:
        pathlib.Path(staging).unlink(missing_ok=True)


def write_once(path, value, uid=0, gid=0, mode=0o600):
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != uid or info.st_gid != gid or stat.S_IMODE(info.st_mode) != mode or info.st_nlink != 1 or path.read_bytes() != value:
            raise RuntimeError('Conflicting existing runtime configuration')
        return
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as stream:
        os.fchmod(stream.fileno(), mode)
        os.fchown(stream.fileno(), uid, gid)
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def load_module(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def runtime_account():
    try:
        account = pwd.getpwnam('forge-runtime')
    except KeyError:
        subprocess.run(['/usr/sbin/useradd', '--system', '--user-group', '--home-dir',
                        '/srv/forge-runtime/home', '--shell', '/usr/sbin/nologin', 'forge-runtime'], check=True)
        account = pwd.getpwnam('forge-runtime')
    if account.pw_uid <= 0 or os.getgrouplist(account.pw_name, account.pw_gid) != [account.pw_gid]:
        raise RuntimeError('Runtime identity must have no supplementary groups')
    if grp.getgrgid(account.pw_gid).gr_name != 'forge-runtime':
        raise RuntimeError('Unexpected runtime group')
    return account


def install(source, control_name, codex_source, database_file, workspace_root, material_root, existing_environment):
    if os.geteuid() != 0:
        raise RuntimeError('System runtime installation requires system privileges')
    control = pwd.getpwnam(control_name)
    runtime = runtime_account()
    if control.pw_uid <= 0 or control.pw_uid == runtime.pw_uid or control.pw_gid == runtime.pw_gid:
        raise RuntimeError('Distinct non-root control and runtime identities required')
    for path, mode in [(pathlib.Path('/etc/forge'), 0o755), (pathlib.Path('/srv/forge-runtime'), 0o755),
                       (pathlib.Path('/srv/forge'), 0o755), (pathlib.Path('/srv/forge/workspaces'), 0o755),
                       (pathlib.Path('/usr/local/libexec'), 0o755), (pathlib.Path('/usr/local/lib/forge-runtime'), 0o755)]:
        protected_directory(path, 0, 0, mode)
    protected_directory(pathlib.Path('/srv/forge-runtime/home'), runtime.pw_uid, runtime.pw_gid, 0o700)
    codex_home = pathlib.Path('/srv/forge-runtime/home/.codex')
    protected_directory(codex_home, runtime.pw_uid, runtime.pw_gid, 0o700)
    for directory in [codex_home / 'plugins', codex_home / '.agents', codex_home / '.agents/plugins']:
        protected_directory(directory, runtime.pw_uid, runtime.pw_gid, 0o700)
    write_once(codex_home / 'config.toml', b'', runtime.pw_uid, runtime.pw_gid)
    protected_directory(workspace_root, control.pw_uid, runtime.pw_gid, 0o2750)
    if runtime.pw_gid not in os.getgrouplist(control.pw_name, control.pw_gid):
        subprocess.run(['/usr/sbin/usermod', '-aG', runtime.pw_name, control.pw_name], check=True)
    helper = pathlib.Path('/usr/local/libexec/forge-runtime-launcher')
    install_file(source / 'scripts/runtime/forge-runtime-launcher.py', helper, 0o755)
    codex_root = pathlib.Path('/usr/local/lib/forge-runtime/codex')
    protected_directory(codex_root, 0, 0, 0o755)
    # Installed vendor artifacts only. No provider credentials or personal config.
    for directory, children, files in os.walk(codex_source):
        relative = pathlib.Path(directory).relative_to(codex_source)
        target_directory = codex_root / relative
        protected_directory(target_directory, 0, 0, 0o755)
        for child in children:
            if (pathlib.Path(directory) / child).is_symlink():
                raise RuntimeError('Codex software source must not contain symlinks')
        for name in files:
            origin = pathlib.Path(directory) / name
            if not stat.S_ISREG(origin.lstat().st_mode):
                raise RuntimeError('Codex software source must contain regular files')
            install_file(origin, target_directory / name, 0o755 if os.access(origin, os.X_OK) else 0o644)
    with (codex_root / 'bin/codex').open('rb') as executable:
        native = executable.read(4) == b'\x7fELF'
    if not native or not (codex_root / 'codex-resources/bwrap').is_file():
        raise RuntimeError('Installed native Codex/resources unavailable')
    base = pathlib.Path('/etc/forge/codex-runtime.toml')
    empty = pathlib.Path('/etc/forge/empty-codex.toml')
    write_once(base, b'', mode=0o644)
    write_once(empty, b'', mode=0o644)
    environment_binary = pathlib.Path('/usr/local/lib/forge-runtime/env')
    install_file(pathlib.Path('/usr/bin/env').resolve(), environment_binary, 0o755)
    config = pathlib.Path('/etc/forge/runtime-launcher.json')
    previous = None
    if config.exists() or config.is_symlink():
        trusted_parent(config)
        info = config.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_gid != 0 or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
            raise RuntimeError('Unsafe existing launcher configuration')
        previous = json.loads(config.read_text())
    data = {'installation': previous['installation'] if previous else str(uuid.uuid4()),
            'control_uid': control.pw_uid, 'runtime_uid': runtime.pw_uid, 'runtime_gid': runtime.pw_gid,
            'runtime_home': '/srv/forge-runtime/home', 'workspace_roots': [str(workspace_root)],
            'agent_unit': 'forge-agent.service', 'max_lifetime_seconds': 7200,
            'codex_binary': str(codex_root / 'bin/codex'), 'codex_config': str(base), 'empty_system_config': str(empty),
            'git_binary': str(pathlib.Path('/usr/bin/git').resolve()), 'env_binary': str(environment_binary)}
    if previous is not None and previous != data:
        raise RuntimeError('Conflicting existing launcher configuration')
    write_once(config, config.read_bytes() if previous else (json.dumps(data, indent=2) + '\n').encode())
    sudoers = (source / 'config/sudoers/forge-runtime.in').read_text().replace('@CONTROL_USER@', control_name)
    with tempfile.TemporaryDirectory() as temporary:
        candidate = pathlib.Path(temporary) / 'sudoers'
        candidate.write_text(sudoers)
        subprocess.run(['/usr/sbin/visudo', '-cf', str(candidate)], check=True, stdout=subprocess.DEVNULL)
        install_file(candidate, pathlib.Path('/etc/sudoers.d/forge-runtime'), 0o440)
    provision = load_module(source / 'scripts/runtime/prepare_mcp.py')
    database_password = database_file.read_bytes()
    retained_database = material_root / 'database.secret'
    if not database_password and retained_database.exists():
        provision.validate_directory(material_root, control.pw_uid)
        database_password = provision.read_existing(retained_database, control.pw_uid)
    if not database_password:
        previous_environment = {}
        if existing_environment.exists():
            for line in existing_environment.read_text().splitlines():
                values = shlex.split(line, comments=True)
                if len(values) == 1 and '=' in values[0]:
                    name, value = values[0].split('=', 1)
                    previous_environment[name] = value
        database_password = previous_environment.get('FORGE_AGENT_DB_PASSWORD', 'forge_agent').encode()
    operator_file = None
    remote_environment = existing_environment.parent / 'forge-remote-nexus.env'
    if remote_environment.exists():
        for line in remote_environment.read_text().splitlines():
            values = shlex.split(line, comments=True)
            if len(values) == 1 and values[0].startswith('FORGE_REMOTE_ACCESS_OPERATOR_SECRET_FILE='):
                operator_file = pathlib.Path(values[0].split('=', 1)[1])
    paths = provision.prepare(material_root, control.pw_uid, control.pw_gid,
                              database_password, 'http://127.0.0.1:9099', operator_file=operator_file)
    # Copy only the former managed subtree, never a personal HOME. The parent was
    # allocated above; copying runs under the control identity with its runtime group.
    subprocess.run(['/usr/sbin/runuser', '-u', control_name, '--', '/usr/bin/python3', '-I',
                    str(source / 'scripts/runtime/prepare_workspaces.py'),
                    str(source / 'forge-projects'), str(workspace_root)], check=True)
    environment = {
        'agent.env': {'FORGE_MCP_KEY_FILE': paths['key'], 'FORGE_MCP_SERVICE_CREDENTIAL_FILE': paths['agent_service'],
                      'FORGE_MCP_DATABASE_CREDENTIAL_FILE': paths['database'], 'FORGE_AGENT_HOST': '127.0.0.1',
                      'FORGE_AGENT_WORKSPACE_ROOT': workspace_root},
        'nexus.env': {'FORGE_MCP_AGENT_SERVICE_CREDENTIAL_FILE': paths['nexus_service'],
                      'FORGE_MCP_BOOTSTRAP_CREDENTIAL_FILE': paths['operator'],
                      'FORGE_MCP_OPERATOR_ORIGIN': 'http://127.0.0.1:9099', 'FORGE_NEXUS_HOST': '127.0.0.1'}}
    for name, values in environment.items():
        content = ''.join(f'{key}="{value}"\n' for key, value in values.items()).encode()
        write_once(material_root / name, content, control.pw_uid, control.pw_gid)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('control_user')
    parser.add_argument('codex_source', type=pathlib.Path)
    parser.add_argument('database_file', type=pathlib.Path)
    parser.add_argument('--existing-environment', type=pathlib.Path, default=pathlib.Path('/etc/forge-ai/forge-ai.env'))
    parser.add_argument('--workspace-root', type=pathlib.Path, default=pathlib.Path('/srv/forge/workspaces/forge-projects'))
    parser.add_argument('--material-root', type=pathlib.Path, default=pathlib.Path('/etc/forge-ai/mcp'))
    args = parser.parse_args()
    try:
        install(args.source, args.control_user, args.codex_source, args.database_file,
                args.workspace_root, args.material_root, args.existing_environment)
    except Exception:
        parser.exit(1, 'Forge protected runtime installation failed; inspect prerequisites without exposing credentials.\n')
