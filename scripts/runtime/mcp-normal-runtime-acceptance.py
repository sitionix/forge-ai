#!/usr/bin/python3 -I
"""Read-only acceptance of the installed normal main runtime, with safe output."""
import argparse
import http.cookiejar
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


def request(opener, url, payload=None):
    headers = {'Origin': 'http://127.0.0.1:9099'}
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
        for unit, name in [('forge-agent.service', 'agent.env'), ('forge-nexus.service', 'nexus.env')]:
            started = subprocess.run(['systemctl', 'show', unit, '--property=ActiveEnterTimestampMonotonic', '--value'],
                                     check=True, capture_output=True, text=True, timeout=10)
            configured = subprocess.run(['systemctl', 'show', unit, '--property=EnvironmentFiles', '--value'],
                                        check=True, capture_output=True, text=True, timeout=10)
            if str(environment.parent / name) not in configured.stdout:
                raise ValueError('Normal generated configuration is not loaded')
            starts.append(int(started.stdout.strip()))
        validate_restart(started_after, starts)
        results['NORMAL_JUST_START'] = 'PASS'
    except Exception:
        results['NORMAL_JUST_START'] = 'FAIL'
        print('NORMAL_JUST_START=FAIL')
        return 1
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
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
    results['MCP_SESSION_ROUTE'] = 'PASS' if status == 401 else 'FAIL'
    results['MCP_CONNECTION_LIST'] = results['EMPTY_STATE'] = results['REAL_BROWSER'] = 'NOT_VERIFIED'
    results['GLOBAL_SIDEBAR'] = results['PROJECTS_STILL_VISIBLE'] = 'NOT_VERIFIED'
    try:
        info = environment.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
            raise ValueError()
        values = dict(shlex.split(line)[0].split('=', 1) for line in environment.read_text().splitlines() if line.strip())
        validate_environment(values)
        operator_file = pathlib.Path(values['FORGE_MCP_BOOTSTRAP_CREDENTIAL_FILE'])
        info = operator_file.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
            raise ValueError()
        operator = operator_file.read_text().strip()
        status, _ = request(opener, SESSION, {'bootstrapSecret': operator})
        if status != 200:
            raise ValueError()
        status, body = request(opener, CONNECTIONS)
        connections = json.loads(body)
        results['MCP_CONNECTION_LIST'] = 'PASS' if status == 200 and isinstance(connections, list) else 'FAIL'
        results['EMPTY_STATE'] = 'PASS' if status == 200 and connections == [] else 'FAIL'
        browser = pathlib.Path(__file__).parents[2] / 'services/forge-console/scripts/mcp-settings-browser-smoke.mjs'
        child = os.environ.copy()
        child.update(FORGE_SETTINGS_BASE_URL=BASE, FORGE_SETTINGS_ACTION='empty',
                     FORGE_SETTINGS_OPERATOR_SECRET_FILE=str(operator_file))
        browser_run = subprocess.run(['node', str(browser)], env=child, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, timeout=60)
        passed = browser_run.returncode == 0 and b'NORMAL_SETTINGS_EMPTY_BROWSER_PASS' in browser_run.stdout
        for name in ['REAL_BROWSER', 'GLOBAL_SIDEBAR', 'PROJECTS_STILL_VISIBLE']:
            results[name] = 'PASS' if passed else 'FAIL'
    except Exception:
        pass  # Credential/configuration failures must never publish transport payloads.
    for name, result in results.items():
        print(f'{name}={result}')
    return 0 if all(result == 'PASS' for result in results.values()) else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--nexus-environment', type=pathlib.Path, default=pathlib.Path('/etc/forge-ai/mcp/nexus.env'))
    parser.add_argument('--started-after', type=int, required=True, help='Monotonic microseconds captured before just stop/start')
    args = parser.parse_args()
    raise SystemExit(check(args.nexus_environment, args.started_after))
