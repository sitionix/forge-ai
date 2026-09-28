#!/usr/bin/env python3
"""Disposable native Codex MCP fixture; synthetic model, grants and local read-only tool."""

import http.server
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import uuid

CODEX = shutil.which('codex')
if not CODEX:
    raise SystemExit('codex CLI is unavailable')

CONNECTION = uuid.UUID('01234567-89ab-4cde-8012-3456789abcde')
ALIAS = 'forge_' + CONNECTION.hex
GRANT_NAME = 'FORGE_MCP_GRANT_' + CONNECTION.hex.upper()
GRANTS = ['synthetic-stage4-grant-a', 'synthetic-stage4-grant-b']
active_grant = GRANTS[0]
tool_calls = []
model_requests = 0
project_requests = 0


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_DELETE(self):
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        global model_requests, project_requests
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        try:
            request = json.loads(body)
        except ValueError:
            self.send_error(400)
            return
        if self.path.startswith('/model'):
            if self.headers.get('Authorization') != 'Bearer synthetic-model-key':
                self.send_error(401)
                return
            model_requests += 1
            if model_requests % 2:
                item = {'id': 'call_fixture', 'type': 'function_call', 'call_id': 'call_stage4',
                        'name': 'echo', 'namespace': 'mcp__' + ALIAS,
                        'arguments': '{"text":"read-only"}', 'status': 'completed'}
            else:
                item = {'id': 'message_fixture', 'type': 'message', 'role': 'assistant',
                        'status': 'completed', 'content': [{'type': 'output_text',
                        'text': 'synthetic native tool result acknowledged', 'annotations': []}]}
            response = {'id': 'response_fixture', 'object': 'response', 'status': 'completed',
                        'output': [item], 'usage': {'input_tokens': 1, 'output_tokens': 1, 'total_tokens': 2}}
            events = [{'type': 'response.created', 'response': dict(response, status='in_progress', output=[])},
                      {'type': 'response.output_item.added', 'output_index': 0, 'item': item},
                      {'type': 'response.output_item.done', 'output_index': 0, 'item': item},
                      {'type': 'response.completed', 'response': response}]
            payload = ''.join('event: ' + event['type'] + '\ndata: ' + json.dumps(event) + '\n\n'
                              for event in events).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if self.path == '/project-sentinel':
            project_requests += 1
        elif self.path != '/internal/mcp/connections/' + str(CONNECTION):
            self.send_error(404)
            return
        elif self.headers.get('Authorization') != 'Bearer ' + active_grant:
            self.send_error(401)
            return
        if 'id' not in request:
            self.send_response(202)
            self.end_headers()
            return
        method = request.get('method')
        if method == 'initialize':
            result = {'protocolVersion': '2025-03-26', 'capabilities': {'tools': {}},
                      'serverInfo': {'name': 'synthetic-read-only', 'version': '0'}}
        elif method == 'tools/list':
            result = {'tools': [{'name': 'echo', 'description': 'Read-only synthetic echo',
                    'inputSchema': {'type': 'object', 'properties': {'text': {'type': 'string'}},
                                    'required': ['text']}}]}
        elif method == 'tools/call':
            if request.get('params', {}).get('name') != 'echo':
                self.send_error(404)
                return
            tool_calls.append(active_grant)
            result = {'content': [{'type': 'text', 'text': 'fixture:read-only'}], 'isError': False}
        else:
            result = {}
        payload = json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': result}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class CodexRpc:
    def __init__(self, cwd, home, grant):
        environment = {'HOME': str(home), 'CODEX_HOME': str(home), 'PATH': os.environ['PATH'],
                       'LANG': 'C.UTF-8', 'GIT_TERMINAL_PROMPT': '0',
                       'FORGE_PROBE_MODEL_KEY': 'synthetic-model-key'}
        if grant is not None:
            environment[GRANT_NAME] = grant
        self.process = subprocess.Popen([CODEX, 'app-server', '--stdio'], cwd=cwd, env=environment,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.responses = queue.Queue()
        self.pending = []
        self.counter = 0

        def read_stdout():
            for line in self.process.stdout:
                try:
                    self.responses.put(json.loads(line))
                except ValueError:
                    pass

        threading.Thread(target=read_stdout, daemon=True).start()
        initialized = self.call('initialize', {'clientInfo': {'name': 'forge_stage4', 'version': '0'},
                                'capabilities': {'experimentalApi': True}})
        assert '/0.157.0' in initialized['result']['userAgent'], 'audited Codex version mismatch: ' + initialized['result']['userAgent']
        self.send({'method': 'initialized', 'params': {}})

    def send(self, value):
        self.process.stdin.write(json.dumps(value) + '\n')
        self.process.stdin.flush()

    def receive(self, predicate, seconds=35):
        for index, value in enumerate(self.pending):
            if predicate(value):
                return self.pending.pop(index)
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                value = self.responses.get(timeout=max(.01, deadline - time.monotonic()))
            except queue.Empty:
                break
            if predicate(value):
                return value
            self.pending.append(value)
        raise TimeoutError('Codex fixture response unavailable')

    def call(self, method, params):
        self.counter += 1
        current = self.counter
        self.send({'id': current, 'method': method, 'params': params})
        response = self.receive(lambda value: value.get('id') == current)
        if 'error' in response:
            raise AssertionError('Codex fixture RPC failed: ' + method)
        return response

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)


def config(base, cwd):
    return {'mcp_servers': {ALIAS: {'url': base + '/internal/mcp/connections/' + str(CONNECTION),
            'bearer_token_env_var': GRANT_NAME, 'enabled_tools': ['echo'],
            'tools': {'echo': {'approval_mode': 'approve'}}}},
            'projects': {str(cwd): {'trust_level': 'untrusted'}},
            'sandbox_workspace_write.network_access': False,
            'web_search': 'disabled', 'features': {'shell_tool': True}, 'agents': {'enabled': False}}


def run():
    global active_grant
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = 'http://127.0.0.1:' + str(server.server_port)
    gateway_base = os.environ.get('FORGE_STAGE4_GATEWAY_BASE', base)
    external_gateway = gateway_base != base
    try:
        with tempfile.TemporaryDirectory(prefix='forge-stage4-native-') as directory:
            root = Path(directory)
            home = root / 'home'
            home.mkdir()
            cwd = root / 'workspace'
            cwd.mkdir()
            (cwd / '.git').mkdir()
            (cwd / '.codex').mkdir()
            (cwd / '.codex/config.toml').write_text(
                    '[mcp_servers.project_sentinel]\nurl = "' + base + '/project-sentinel"\n')
            (home / 'config.toml').write_text('model = "synthetic-model"\nmodel_provider = "fixture"\n'
                    '[model_providers.fixture]\nname = "fixture"\nbase_url = "' + base + '/model"\n'
                    'wire_api = "responses"\nenv_key = "FORGE_PROBE_MODEL_KEY"\n')
            thread_id = None
            for index, grant in enumerate(GRANTS):
                rpc = CodexRpc(cwd, home, grant)
                try:
                    if index == 0:
                        started = rpc.call('thread/start', {'cwd': str(cwd), 'model': 'synthetic-model',
                                'modelProvider': 'fixture', 'approvalPolicy': 'never',
                                'sandbox': 'workspace-write', 'ephemeral': False,
                                'config': config(gateway_base, cwd)})
                        thread_id = started['result']['thread']['id']
                    else:
                        resumed = rpc.call('thread/resume', {'threadId': thread_id, 'excludeTurns': True,
                                'developerInstructions': 'Use only approved Forge MCP tools.',
                                'cwd': str(cwd), 'sandbox': 'workspace-write', 'approvalPolicy': 'never',
                                'runtimeWorkspaceRoots': [str(cwd)],
                                'config': config(gateway_base, cwd)})
                        assert resumed['result']['thread']['id'] == thread_id
                    inventory = rpc.call('mcpServerStatus/list', {'threadId': thread_id, 'limit': 100})['result']
                    assert inventory['nextCursor'] is None
                    assert {entry['name'] for entry in inventory['data']} == {ALIAS}
                    assert set(inventory['data'][0]['tools']) == {'echo'}
                    turn = rpc.call('turn/start', {'threadId': thread_id, 'input': [
                            {'type': 'text', 'text': 'Use the read-only tool and finish.', 'text_elements': []}]})
                    turn_id = turn['result']['turn']['id']
                    finished = rpc.receive(lambda value: value.get('method') == 'turn/completed'
                            and value.get('params', {}).get('turn', {}).get('id') == turn_id)
                    assert finished['params']['turn']['status'] == 'completed'
                finally:
                    rpc.close()
                if not external_gateway:
                    assert len(tool_calls) == index + 1
                    assert tool_calls[-1] == grant
                if index == 0 and not external_gateway:
                    active_grant = GRANTS[1]
                    import urllib.error
                    import urllib.request
                    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list',
                                       'params': {}}).encode()
                    request = urllib.request.Request(base + '/internal/mcp/connections/' + str(CONNECTION),
                            data=body, headers={'Authorization': 'Bearer ' + GRANTS[0]})
                    try:
                        urllib.request.urlopen(request, timeout=2)
                        raise AssertionError('revoked grant was accepted')
                    except urllib.error.HTTPError as error:
                        assert error.code == 401
            recovery = CodexRpc(cwd, home, None)
            try:
                read = recovery.call('thread/read', {'threadId': thread_id, 'includeTurns': True})
                assert read['result']['thread']['id'] == thread_id
            finally:
                recovery.close()
            assert project_requests == 0
            if not external_gateway:
                assert tool_calls == GRANTS
            print('STAGE4_NATIVE_ASSERTION PASS')
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    run()
