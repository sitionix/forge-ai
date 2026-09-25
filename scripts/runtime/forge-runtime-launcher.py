#!/usr/bin/python3 -I
"""Narrow root-owned systemd launcher. Install only after disposable acceptance.
No caller-selected config, executable, environment, property, PID or unit name.
"""
import fcntl
import errno
import grp
import io
import json
import os
from pathlib import Path
import pwd
import re
import select
import signal
import stat
import subprocess
import sys
import time
import tomllib
import uuid

CONFIG = Path('/etc/forge/runtime-launcher.json')
RECEIPTS = Path('/run/forge-runtime')
SYSTEMCTL = '/usr/bin/systemctl'
SYSTEMD_RUN = '/usr/bin/systemd-run'
SAFE_ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'}
MAX_GRANT_ENVELOPE = 262144
GRANT_NAME = re.compile(r'FORGE_MCP_GRANT_[0-9A-F]{32}\Z')
GRANT_VALUE = re.compile(r'[A-Za-z0-9_-]{1,256}\Z')


def identifier(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('invalid execution identity')
    return value


def trusted_path(path, directory=False):
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('unsafe installation')
    for item in [*reversed(path.parents), path]:
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('unsafe installation')
    info = path.stat()
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise ValueError('unsafe installation')
    return path


def load_config():
    trusted_path(Path(__file__))
    trusted_path(Path(sys.executable).resolve())
    trusted_path(CONFIG)
    data = json.loads(CONFIG.read_text())
    required = {'installation', 'control_uid', 'runtime_uid', 'runtime_gid', 'runtime_home',
                'workspace_roots', 'agent_unit', 'max_lifetime_seconds', 'codex_binary',
                'git_binary', 'env_binary', 'codex_config', 'empty_system_config'}
    if not isinstance(data, dict) or set(data) != required:
        raise ValueError('unsafe configuration')
    identifier(data['installation'])
    fixed_paths = [data.get(key) for key in ['runtime_home','codex_binary','git_binary','env_binary',
                                             'codex_config','empty_system_config']]
    if not isinstance(data.get('workspace_roots'), list): raise ValueError('unsafe configuration')
    fixed_paths.extend(data['workspace_roots'])
    if any(not isinstance(path,str) or not re.fullmatch(r'/[A-Za-z0-9_./-]+',path) for path in fixed_paths):
        raise ValueError('unsafe configured path')
    for key in ['control_uid', 'runtime_uid', 'runtime_gid', 'max_lifetime_seconds']:
        if type(data[key]) is not int or data[key] <= 0:
            raise ValueError('unsafe configuration')
    if data['control_uid'] == data['runtime_uid'] or not 5500 <= data['max_lifetime_seconds'] <= 14400:
        raise ValueError('unsafe identity or lifetime')
    for name in ['root','sudo','wheel','docker','lxd','incus','libvirt','disk','shadow','adm','systemd-journal']:
        try:
            if grp.getgrnam(name).gr_gid == data['runtime_gid']: raise ValueError('privileged runtime group')
        except KeyError: pass
    account = pwd.getpwuid(data['runtime_uid'])
    control_account = pwd.getpwuid(data['control_uid'])
    if data['runtime_gid'] == control_account.pw_gid:
        raise ValueError('control primary group shared with runtime')
    if account.pw_gid != data['runtime_gid'] or os.getgrouplist(account.pw_name, account.pw_gid) != [account.pw_gid]:
        raise ValueError('runtime supplementary groups forbidden')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}\.service', data['agent_unit']):
        raise ValueError('unsafe Agent unit')
    for key in ['codex_binary', 'git_binary', 'env_binary']:
        trusted_path(data[key])
        if not os.access(data[key], os.X_OK):
            raise ValueError('missing executable')
    for key in ['codex_config', 'empty_system_config']:
        trusted_path(data[key])
    base = tomllib.loads(Path(data['codex_config']).read_text())
    if any(key in base for key in ['mcp_servers', 'plugins', 'marketplaces']) or tomllib.loads(
            Path(data['empty_system_config']).read_text()):
        raise ValueError('unsafe Codex base configuration')
    for binary in [SYSTEMCTL, SYSTEMD_RUN]:
        trusted_path(Path(binary).resolve())
    home = Path(data['runtime_home'])
    if not home.is_absolute() or home.resolve() != home or home.stat().st_uid != data['runtime_uid']:
        raise ValueError('unsafe runtime home')
    trusted_path(home.parent, directory=True)
    if home.stat().st_mode & 0o077:
        raise ValueError('unsafe runtime home permissions')
    roots = data['workspace_roots']
    if not isinstance(roots, list) or not roots:
        raise ValueError('missing workspace roots')
    for root in roots:
        path = Path(root)
        if not path.is_absolute() or path.resolve() != path or not path.is_dir():
            raise ValueError('unsafe workspace root')
        trusted_path(path.parent, directory=True)
        if path.stat().st_uid not in [0, data['control_uid']] or path.stat().st_mode & 0o022:
            raise ValueError('unsafe workspace ownership')
        if home == path or home.is_relative_to(path) or path.is_relative_to(home):
            raise ValueError('runtime home overlaps workspace')
    return data


def caller(config):
    if os.geteuid() != 0 or os.getuid() != 0 or os.environ.get('SUDO_UID') != str(config['control_uid']):
        raise ValueError('unauthorized caller')


def unit_name(config, execution):
    return 'forge-runtime-' + identifier(config['installation']) + '-' + identifier(execution) + '.service'


def service_command(config, execution, cwd, command, credential_path=None):
    props = ['User='+str(config['runtime_uid']), 'Group='+str(config['runtime_gid']),
             'SupplementaryGroups=', 'NoNewPrivileges=yes', 'CapabilityBoundingSet=',
             'AmbientCapabilities=', 'KillMode=control-group', 'TimeoutStopSec=5s',
             'SendSIGKILL=yes', 'RuntimeMaxSec='+str(config['max_lifetime_seconds']),
             'BindsTo='+config['agent_unit'], 'After='+config['agent_unit'],
             'PartOf='+config['agent_unit'], 'ProtectSystem=strict', 'ProtectHome=yes',
             'ProtectControlGroups=yes', 'ProtectProc=invisible',
             'PrivateTmp=yes', 'PrivateDevices=yes', 'RestrictSUIDSGID=yes',
             'Delegate=no', 'UMask=0007',
             'WorkingDirectory='+cwd,
             'ReadWritePaths='+config['runtime_home']+' '+' '.join(config['workspace_roots'])]
    if credential_path is not None:
        codex_home = config['runtime_home']+'/.codex'
        props.extend(['LoadCredential=forge-mcp-grants:'+str(credential_path),
                      'BindReadOnlyPaths='+config['codex_config']+':'+codex_home+'/config.toml',
                      'BindReadOnlyPaths='+config['empty_system_config']+':/etc/codex/config.toml',
                      'InaccessiblePaths='+codex_home+'/plugins',
                      'InaccessiblePaths='+codex_home+'/.agents/plugins'])
    launch_command = command if credential_path is not None else [
        config['env_binary'], '-i', 'HOME='+config['runtime_home'],
        'CODEX_HOME='+config['runtime_home']+'/.codex', 'PATH=/usr/bin:/bin',
        'LANG=C.UTF-8', 'GIT_TERMINAL_PROMPT=0', *command]
    return [SYSTEMD_RUN, '--quiet', '--pipe', '--wait', '--collect', '--service-type=exec',
            '--unit='+unit_name(config, execution), *['--property='+p for p in props], '--',
            *launch_command]


def validate_codex_mount_targets(config):
    home = Path(config['runtime_home'])
    paths = [(home/'.codex', True), (home/'.codex/config.toml', False),
             (home/'.codex/plugins', True), (home/'.codex/.agents', True),
             (home/'.codex/.agents/plugins', True)]
    for path, directory in paths:
        info = path.lstat()
        expected = stat.S_ISDIR if directory else stat.S_ISREG
        if (not expected(info.st_mode) or info.st_uid not in (0, config['runtime_uid'])
                or info.st_mode & 0o022):
            raise ValueError('unsafe Codex mount target')


def control(*args):
    result = subprocess.run([SYSTEMCTL, *args], env=SAFE_ENV, stdin=subprocess.DEVNULL,
                            capture_output=True, text=True, timeout=8)
    return result


def state(unit):
    keys = ['LoadState', 'ActiveState', 'SubState', 'MainPID', 'ControlGroup']
    result = control('show', unit, *['--property='+key for key in keys])
    values = {}
    for line in result.stdout.splitlines():
        key, sep, value = line.partition('=')
        if not sep or key in values:
            raise ValueError('unit state unavailable')
        values[key] = value
    if result.returncode or set(values) != set(keys):
        raise ValueError('unit state unavailable')
    return values


def cgroup_empty(group):
    if not group:
        return True
    path = Path('/sys/fs/cgroup'+group)
    if not group.startswith('/') or '..' in Path(group).parts or not path.is_relative_to('/sys/fs/cgroup'):
        raise ValueError('invalid cgroup')
    if not path.exists():
        return True
    events = dict(line.split() for line in (path/'cgroup.events').read_text().splitlines())
    return events.get('populated') == '0'


def stop_unit(config, execution):
    unit = unit_name(config, execution)
    before = state(unit)
    result = control('stop', unit)
    after = state(unit)
    absent = after['LoadState'] == 'not-found'
    if (result.returncode and not absent) or after['ActiveState'] not in ['inactive', 'failed'] or after['MainPID'] != '0':
        raise ValueError('owned cleanup unconfirmed')
    if not cgroup_empty(before['ControlGroup']) or not cgroup_empty(after['ControlGroup']):
        raise ValueError('owned cleanup unconfirmed')


def receipt_directory(config):
    if not RECEIPTS.exists():
        RECEIPTS.mkdir(mode=0o700)
    trusted_path(RECEIPTS, directory=True)
    folder = RECEIPTS/config['installation']
    folder.mkdir(mode=0o700, exist_ok=True)
    trusted_path(folder, directory=True)
    return folder


def locked_receipt(config, execution):
    path = receipt_directory(config)/(identifier(execution)+'.json')
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    if os.fstat(fd).st_uid != 0 or os.fstat(fd).st_nlink != 1:
        os.close(fd)
        raise ValueError('unsafe receipt')
    file = os.fdopen(fd, 'r+')
    deadline = time.monotonic() + 20
    while True:
        try:
            fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return file
        except BlockingIOError:
            if time.monotonic() >= deadline:
                file.close()
                raise ValueError('owned lifecycle busy')
            time.sleep(.05)


def credential_path(config, execution):
    return RECEIPTS/identifier(config['installation'])/(identifier(execution)+'.credential')


def validate_grants(value):
    if not isinstance(value, dict) or any(not isinstance(key, str) or not GRANT_NAME.fullmatch(key)
            or not isinstance(token, str) or not GRANT_VALUE.fullmatch(token)
            for key, token in value.items()):
        raise ValueError('invalid runtime grant envelope')
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError('duplicate runtime grant name')
        result[key] = value
    return result


def read_startup_envelope(reader):
    payload = bytearray()
    deadline = time.monotonic() + 5
    try: descriptor = reader.fileno()
    except (AttributeError, OSError, io.UnsupportedOperation): descriptor = None
    while len(payload) <= MAX_GRANT_ENVELOPE:
        if descriptor is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([descriptor], [], [], remaining)[0]:
                raise ValueError('runtime grant envelope timed out')
        chunk = reader.read(1)
        if not chunk: raise ValueError('incomplete runtime grant envelope')
        if chunk == b'\n':
            try: return validate_grants(json.loads(payload, object_pairs_hook=unique_object))
            except (json.JSONDecodeError, UnicodeDecodeError) as error:
                raise ValueError('invalid runtime grant envelope') from error
        payload.extend(chunk)
    raise ValueError('runtime grant envelope too large')


def write_credential(config, execution, grants):
    receipt_directory(config)
    path = credential_path(config, execution)
    encoded = json.dumps(validate_grants(grants), separators=(',', ':'), sort_keys=True).encode()
    if len(encoded) > MAX_GRANT_ENVELOPE: raise ValueError('runtime grant envelope too large')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
    try:
        if os.fstat(fd).st_uid != os.geteuid() or os.fstat(fd).st_nlink != 1:
            raise ValueError('unsafe credential source')
        remaining = memoryview(encoded)
        while remaining:
            written = os.write(fd, remaining)
            if written <= 0: raise OSError('credential source write failed')
            remaining = remaining[written:]
        os.fsync(fd)
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    finally:
        os.close(fd)
    return path


def runtime_codex(binary, home):
    folder = os.environ.get('CREDENTIALS_DIRECTORY')
    if not folder or not re.fullmatch(r'/[A-Za-z0-9_./-]+', folder):
        raise ValueError('missing runtime credentials')
    source = Path(folder)/'forge-mcp-grants'
    payload = source.read_bytes()
    if len(payload) > MAX_GRANT_ENVELOPE: raise ValueError('runtime credentials too large')
    grants = validate_grants(json.loads(payload, object_pairs_hook=unique_object))
    environment = {'HOME':home, 'CODEX_HOME':home+'/.codex', 'PATH':'/usr/bin:/bin',
                   'LANG':'C.UTF-8', 'GIT_TERMINAL_PROMPT':'0'}
    environment.update(grants)
    os.execve(binary, [binary, 'app-server', '--stdio'], environment)


def stop(config, execution):
    with locked_receipt(config, execution) as receipt:
        value = receipt.read()
        if not value:
            # Tombstone prevents stop-before-start racing into an orphan later.
            receipt.write(json.dumps({'state':'stopped'})); receipt.flush()
            return
        if value != json.dumps({'state':'stopped'}):
            stop_unit(config, execution)
            receipt.seek(0); receipt.truncate(); receipt.write(json.dumps({'state':'stopped'})); receipt.flush()
        credential_path(config, execution).unlink(missing_ok=True)


def start(config, execution, cwd, command, probe_input=None, codex_grants=None):
    child = None
    claimed = False
    credential = None
    def terminate(signum, frame):
        raise InterruptedError('launcher interrupted')
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    try:
        with locked_receipt(config, execution) as receipt:
            if receipt.read():
                raise ValueError('execution already used')
            receipt.seek(0); receipt.write(json.dumps({'state':'starting'})); receipt.flush()
            claimed = True
            if codex_grants is not None:
                validate_codex_mount_targets(config)
                credential = write_credential(config, execution, codex_grants)
                command = [str(Path(sys.executable).resolve()), '-I', str(Path(__file__)),
                           '--runtime-codex', config['codex_binary'], config['runtime_home']]
            child = subprocess.Popen(service_command(config, execution, cwd, command, credential), env=SAFE_ENV,
                                     stdin=subprocess.PIPE if probe_input is not None else None,
                                     stdout=subprocess.PIPE if probe_input is not None else None,
                                     stderr=subprocess.DEVNULL if probe_input is not None else None)
            # Serialize start acknowledgement with stop, including fast failed/collected units.
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                current = state(unit_name(config, execution))
                if current['LoadState'] == 'loaded' or child.poll() is not None:
                    break
                time.sleep(.05)
            else:
                raise ValueError('runtime start unconfirmed')
        if probe_input is not None:
            output, _ = child.communicate(probe_input, timeout=20)
            if child.returncode or len(output) > 8192:
                raise ValueError('runtime probe failed')
            return json.loads(output)
        return child.wait()
    finally:
        try:
            if claimed: stop(config, execution)
        finally:
            if credential is not None: credential.unlink(missing_ok=True)
            if child is not None and child.poll() is None:
                child.terminate()
                try: child.wait(timeout=5)
                except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=5)


def git_command(config, arguments):
    # Existing Git adapter uses absolute -C operands. Trust only each validated
    # managed checkout, never global '*'; shared control ownership is intentional.
    trust = []
    for index, argument in enumerate(arguments):
        if argument == '-C':
            if index + 1 == len(arguments): raise ValueError('missing working directory')
            trust.extend(['-c', 'safe.directory=' + workspace(config, arguments[index+1])])
    return [config['git_binary'], *trust, *arguments]


def workspace(config, value):
    path = Path(value)
    if not re.fullmatch(r'/[A-Za-z0-9_./-]+', value) or path.resolve(strict=True) != path or not path.is_dir():
        raise ValueError('unsafe working directory')
    if not any(path.is_relative_to(root) for root in map(Path, config['workspace_roots'])):
        raise ValueError('unmanaged working directory')
    return str(path)


def preflight(config):
    if not Path('/sys/fs/cgroup/cgroup.controllers').is_file():
        raise ValueError('cgroup v2 unavailable')
    result = control('show', config['agent_unit'], '--property=ActiveState', '--value')
    if result.returncode or result.stdout.strip() != 'active':
        raise ValueError('Agent lifecycle unavailable')
    for path in receipt_directory(config).glob('*.json'):
        stop(config, path.stem)


def denied(path, flags):
    if flags == os.O_WRONLY and os.path.isdir(path):
        return not os.access(path, os.W_OK)
    try:
        descriptor = os.open(path, flags | os.O_CLOEXEC)
        os.close(descriptor)
        return False
    except OSError as error:
        if isinstance(error, (PermissionError, FileNotFoundError, NotADirectoryError)) or error.errno == errno.EROFS:
            return True
        raise


def runtime_report(request):
    paths = request['protected_paths']
    pid = request['control_pid']
    return {'uid':os.getuid(), 'gid':os.getgid(),
            'protected_denied':all(denied(p, os.O_RDONLY) and denied(p, os.O_WRONLY) for p in paths),
            'control_write_denied':all(denied(p, os.O_WRONLY) for p in request.get('immutable_paths', [])),
            'proc_denied':all(denied('/proc/'+str(pid)+'/'+p, os.O_RDONLY) for p in ['environ','fd','cwd']),
            'environment_clean':set(os.environ) <= {'HOME','CODEX_HOME','PATH','LANG','GIT_TERMINAL_PROMPT','LC_CTYPE'},
            'cgroup':Path('/proc/self/cgroup').read_text().strip()}


def probe(config, request):
    if (not isinstance(request, dict) or set(request) != {'protected_paths','control_pid','reconcile'}
            or type(request['control_pid']) is not int or request['control_pid'] <= 0
            or type(request['reconcile']) is not bool
            or not isinstance(request['protected_paths'], list) or len(request['protected_paths']) > 32
            or any(not isinstance(p,str) or not p.startswith('/') or len(p)>4096 for p in request['protected_paths'])):
        raise ValueError('invalid probe')
    if request['reconcile']:
        preflight(config)
    execution = str(uuid.uuid4())
    # The runtime process sees paths only, never their contents; fixed script runs with -I.
    request['protected_paths'] = request['protected_paths'] + [str(CONFIG)]
    request['immutable_paths'] = [str(Path(__file__)), *config['workspace_roots']]
    report = start(config, execution, config['workspace_roots'][0],
                   [str(Path(sys.executable).resolve()), '-I', str(Path(__file__)), '--runtime-probe'],
                   json.dumps(request).encode())
    expected_group = '0::/system.slice/' + unit_name(config, execution)
    if (set(report) != {'uid','gid','protected_denied','control_write_denied','proc_denied','environment_clean','cgroup'}
            or report['uid'] != config['runtime_uid'] or report['gid'] != config['runtime_gid']
            or report['protected_denied'] is not True or report['control_write_denied'] is not True or report['proc_denied'] is not True
            or report['environment_clean'] is not True or report['cgroup'] != expected_group):
        raise ValueError('runtime isolation unconfirmed')
    print(json.dumps({'runtimeUid': report['uid'], 'controlUid': config['control_uid'],
                      'protectedPathsDenied': True, 'processAliasesDenied':True,
                      'environmentClean':True, 'ownedCleanupConfirmed':True}))
    return 0


def read_request():
    payload = bytearray()
    deadline = time.monotonic() + 5
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([sys.stdin.buffer], [], [], remaining)[0]:
            raise ValueError('probe input timed out')
        chunk = os.read(sys.stdin.fileno(), 16385-len(payload))
        if not chunk:
            return json.loads(payload)
        payload.extend(chunk)
        if len(payload) > 16384:
            raise ValueError('probe input too large')


def main(args):
    config = load_config()
    caller(config)
    if args == ['probe']:
        return probe(config, read_request())
    if len(args) == 2 and args[0] == 'stop':
        stop(config, args[1]); return 0
    if len(args) >= 4 and args[0] == 'start':
        kind, execution = args[1:3]
        identifier(execution)
        if kind == 'codex' and len(args) == 4:
            reader = os.fdopen(os.dup(sys.stdin.fileno()), 'rb', buffering=0)
            try: grants = read_startup_envelope(reader)
            finally: reader.close()
            return start(config, execution, workspace(config, args[3]),
                         [config['codex_binary'], 'app-server', '--stdio'], codex_grants=grants)
        if kind == 'git':
            if sum(map(len, args)) > 131072: raise ValueError('argument limit exceeded')
            return start(config, execution, config['workspace_roots'][0], git_command(config, args[3:]))
    raise ValueError('invalid request')


if __name__ == '__main__':
    try:
        if len(sys.argv) == 4 and sys.argv[1] == '--runtime-codex' and os.geteuid() != 0:
            runtime_codex(sys.argv[2], sys.argv[3])
        if sys.argv[1:] == ['--runtime-probe'] and os.geteuid() != 0:
            print(json.dumps(runtime_report(read_request())))
            sys.exit(0)
        sys.exit(main(sys.argv[1:]))
    except Exception:
        print('Runtime boundary unavailable', file=sys.stderr)
        sys.exit(1)
