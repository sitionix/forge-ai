#!/usr/bin/python3 -I
"""Read-only acceptance of the installed normal main runtime, with safe output."""
import argparse
import json
import os
import pathlib
import shlex
import stat
import subprocess
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:9099/fgaisox'
SESSION = BASE + '/api/v1/operator/session'
CONNECTIONS = BASE + '/api/v1/infrastructure/agents/integrations/mcp/connections'


def validate_restart(marker, starts):
    if not isinstance(marker, int) or marker <= 0 or len(starts) != 2 or any(start <= marker for start in starts):
        raise ValueError('Fresh main Agent and Nexus restart required')


def validate_environment(values):
    removed_property = 'forge.mcp.' + 'enabled'
    if any(name.startswith('FORGE_MCP_') and name.endswith('_ENABLED') for name in values):
        raise ValueError('Activation overrides are not normal runtime')
    if any(removed_property in values.get(name, '') for name in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS']):
        raise ValueError('Activation overrides are not normal runtime')


def environment_values(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
        raise ValueError('Normal generated environment must be protected')
    return dict(shlex.split(line)[0].split('=', 1) for line in path.read_text().splitlines() if line.strip())


def validate_effective_configuration(expected, actual, arguments):
    validate_environment(actual)
    if any(actual.get(name) != value for name, value in expected.items()):
        raise ValueError('Runtime differs from generated configuration')
    overrides = list(arguments) + [actual.get(name, '') for name in
                                   ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS')]
    if actual.get('SPRING_APPLICATION_JSON') or any('forge.mcp.' in value or 'spring.config.' in value for value in overrides):
        raise ValueError('Manual configuration cannot qualify as normal runtime')


def request(opener, url, payload=None):
    headers = {}
    body = None
    if payload is not None:
        body = json.dumps(payload).encode()
        headers['Content-Type'] = 'application/json'
    try:
        with opener.open(urllib.request.Request(url, body, headers), timeout=10) as response:
            return response.status, response.read(1048576)
    except urllib.error.HTTPError as response:
        return response.code, b''
    except Exception:
        return None, b''


def check(environment, started_after):
    results = {}
    try:
        validate_environment(os.environ)
        starts = []
        for unit, name in [('forge-agent.service', 'agent.env'), ('forge-nexus.service', None)]:
            started = subprocess.run(['systemctl', 'show', unit, '--property=ActiveEnterTimestampMonotonic', '--value'],
                                     check=True, capture_output=True, text=True, timeout=10)
            configured = subprocess.run(['systemctl', 'show', unit, '--property=EnvironmentFiles', '--value'],
                                        check=True, capture_output=True, text=True, timeout=10)
            if name and str(environment.parent / name) not in configured.stdout:
                raise ValueError('Normal generated configuration is not loaded')
            process = subprocess.run(['systemctl', 'show', unit, '--property=MainPID', '--value'],
                                     check=True, capture_output=True, text=True, timeout=10)
            process_root = pathlib.Path('/proc') / str(int(process.stdout.strip()))
            actual = dict(entry.decode().split('=', 1) for entry in (process_root / 'environ').read_bytes().split(b'\0') if b'=' in entry)
            arguments = [entry.decode() for entry in (process_root / 'cmdline').read_bytes().split(b'\0') if entry]
            validate_effective_configuration(environment_values(environment.parent / name) if name else {}, actual, arguments)
            starts.append(int(started.stdout.strip()))
        validate_restart(started_after, starts)
        results['NORMAL_JUST_START'] = 'PASS'
    except Exception:
        results['NORMAL_JUST_START'] = 'FAIL'
        print('NORMAL_JUST_START=FAIL')
        return 1
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for name, url in [('MAIN_NEXUS_HEALTH', BASE + '/actuator/health'),
                      ('MAIN_AGENT_HEALTH', 'http://127.0.0.1:7091/actuator/health')]:
        status, body = request(opener, url)
        try:
            healthy = status == 200 and json.loads(body)['status'] == 'UP'
        except Exception:
            healthy = False
        results[name] = 'PASS' if healthy else 'FAIL'
    status, body = request(opener, BASE + '/operator/settings.html')
    results['SETTINGS_HTTP'] = 'PASS' if status == 200 and b'mcpIntegrations' in body else 'FAIL'
    status, _ = request(opener, SESSION)
    results['MCP_SESSION_ROUTE'] = 'PASS' if status == 404 else 'FAIL'
    results['MCP_CONNECTION_LIST'] = results['EMPTY_STATE'] = results['REAL_BROWSER'] = 'NOT_VERIFIED'
    results['GLOBAL_SIDEBAR'] = results['PROJECTS_STILL_VISIBLE'] = 'NOT_VERIFIED'
    try:
        status, body = request(opener, CONNECTIONS)
        connections = json.loads(body)
        results['MCP_CONNECTION_LIST'] = 'PASS' if status == 200 and isinstance(connections, list) else 'FAIL'
        results['EMPTY_STATE'] = 'PASS' if status == 200 and connections == [] else 'FAIL'
        browser = pathlib.Path(__file__).parents[2] / 'services/forge-console/scripts/mcp-settings-browser-smoke.mjs'
        child = os.environ.copy()
        child.update(FORGE_SETTINGS_BASE_URL=BASE, FORGE_SETTINGS_ACTION='empty')
        browser_run = subprocess.run(['node', str(browser)], env=child, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, timeout=60)
        passed = browser_run.returncode == 0 and b'NORMAL_SETTINGS_EMPTY_BROWSER_PASS' in browser_run.stdout
        for name in ['REAL_BROWSER', 'GLOBAL_SIDEBAR', 'PROJECTS_STILL_VISIBLE']:
            results[name] = 'PASS' if passed else 'FAIL'
    except Exception:
        pass  # Configuration failures must never publish transport payloads.
    for name, result in results.items():
        print(f'{name}={result}')
    return 0 if all(result == 'PASS' for result in results.values()) else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--agent-environment', type=pathlib.Path, default=pathlib.Path('/etc/forge-ai/mcp/agent.env'))
    parser.add_argument('--started-after', type=int, required=True, help='Monotonic microseconds captured before just stop/start')
    args = parser.parse_args()
    raise SystemExit(check(args.agent_environment, args.started_after))
