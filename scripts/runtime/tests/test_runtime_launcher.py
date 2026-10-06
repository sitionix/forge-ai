"""Unit contract tests only; mocked systemd is never isolation evidence."""
import importlib.util
import pathlib
import tempfile
import unittest
import errno
import io
import os
import sys
import json
import subprocess
import select
import time
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).parents[1] / 'forge-runtime-launcher.py'

class LauncherTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if SOURCE.exists():
            spec = importlib.util.spec_from_file_location('launcher', SOURCE)
            cls.launcher = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cls.launcher)

    def test_implementation_exists(self):
        self.assertTrue(SOURCE.exists(), 'fixed runtime launcher is missing')

    def test_isolation_config_parsing_accepts_settings_and_rejects_external_tools(self):
        with tempfile.TemporaryDirectory() as folder:
            base = pathlib.Path(folder) / 'base.toml'
            empty = pathlib.Path(folder) / 'empty.toml'
            empty.write_text('')
            profile = {'codex_config': str(base), 'empty_system_config': str(empty)}
            # Disposable fixtures cannot satisfy the production root-owned path check.
            with patch.object(self.launcher, 'trusted_path', side_effect=lambda path: pathlib.Path(path)):
                base.write_text('model = "gpt-5"\n')
                self.launcher.validate_codex_isolation_config(profile)
                for content in ['[mcp_servers.github]\nurl = "https://example.com/mcp"\n',
                                '[plugins]\nenabled = true\n', '[marketplaces]\nenabled = true\n']:
                    base.write_text(content)
                    with self.subTest(content=content), self.assertRaisesRegex(ValueError, 'unsafe Codex base'):
                        self.launcher.validate_codex_isolation_config(profile)
                base.write_text('model = "gpt-5"\n')
                empty.write_text('[mcp_servers.github]\nurl = "https://example.com/mcp"\n')
                with self.assertRaisesRegex(ValueError, 'unsafe Codex base'):
                    self.launcher.validate_codex_isolation_config(profile)

    def test_noncanonical_identifiers_denied(self):
        if not SOURCE.exists(): self.skipTest('implementation absent')
        for value in ['../other', 'A'*36, '1-1-1-1-1', '--unit=other']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.launcher.identifier(value)

    def test_profile_has_owned_cleanup_and_clean_environment(self):
        if not SOURCE.exists(): self.skipTest('implementation absent')
        config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', runtime_uid=62002,
                      runtime_gid=62002, runtime_home='/srv/forge/runtime', agent_unit='forge-agent.service',
                      max_lifetime_seconds=7200, workspace_roots=['/srv/forge/workspaces'],
                      env_binary='/usr/bin/env', codex_binary='/opt/forge/codex', git_binary='/usr/bin/git')
        command = self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                                                '/srv/forge/workspaces/x', ['/usr/bin/git', 'status'])
        for prop in ['NoNewPrivileges=yes','KillMode=control-group','ProtectControlGroups=yes',
                     'ProtectProc=invisible','CapabilityBoundingSet=','SupplementaryGroups=','BindsTo=forge-agent.service',
                     'RuntimeMaxSec=7200','User=62002','Group=62002']:
            self.assertIn('--property='+prop, command)
        self.assertNotIn('--property=ProcSubset=pid', command)
        self.assertEqual(command[command.index('--')+1:], ['/usr/bin/env', '-i', 'HOME=/srv/forge/runtime',
                         'CODEX_HOME=/srv/forge/runtime/.codex', 'PATH=/usr/bin:/bin', 'LANG=C.UTF-8',
                         'GIT_TERMINAL_PROMPT=0', '/usr/bin/git', 'status'])

    def test_codex_service_uses_credential_path_and_masks_unapproved_configuration(self):
        config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', runtime_uid=62002,
                      runtime_gid=62002, runtime_home='/srv/forge/runtime', agent_unit='forge-agent.service',
                      max_lifetime_seconds=7200, workspace_roots=['/srv/forge/workspaces'],
                      env_binary='/usr/bin/env', codex_binary='/opt/forge/codex', git_binary='/usr/bin/git',
                      codex_config='/etc/forge/codex-runtime.toml', empty_system_config='/etc/forge/empty-codex.toml')
        credential='/run/forge-runtime/owned/turn.credential'
        bootstrap=[str(pathlib.Path(sys.executable).resolve()), '-I', str(SOURCE),
                   '--runtime-codex', '/opt/forge/codex', '/srv/forge/runtime']
        command=self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                                              '/srv/forge/workspaces/x', bootstrap, credential)
        properties=[part for part in command if part.startswith('--property=')]
        self.assertIn('--property=LoadCredential=forge-mcp-grants:'+credential, properties)
        self.assertIn('--property=BindReadOnlyPaths=/etc/forge/codex-runtime.toml:/srv/forge/runtime/.codex/config.toml', properties)
        self.assertIn('--property=BindReadOnlyPaths=/etc/forge/empty-codex.toml:/etc/codex/config.toml', properties)
        self.assertIn('--property=InaccessiblePaths=/srv/forge/runtime/.codex/plugins', properties)
        self.assertIn('--property=InaccessiblePaths=/srv/forge/runtime/.codex/.agents/plugins', properties)
        self.assertNotIn('synthetic-grant', ' '.join(command))
        self.assertEqual(command[command.index('--')+1:][:4],
                         [str(pathlib.Path(sys.executable).resolve()), '-I', str(SOURCE), '--runtime-codex'])

    def test_non_mcp_codex_has_same_config_isolation_as_mcp(self):
        config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', runtime_uid=62002,
                      runtime_gid=62002, runtime_home='/srv/forge/runtime', agent_unit='forge-agent.service',
                      max_lifetime_seconds=7200, workspace_roots=['/srv/forge/workspaces'],
                      env_binary='/usr/bin/env', codex_binary='/opt/forge/codex', git_binary='/usr/bin/git',
                      codex_config='/etc/forge/codex-runtime.toml', empty_system_config='/etc/forge/empty-codex.toml')
        command = self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                '/srv/forge/workspaces/x', ['/opt/forge/codex', 'app-server', '--stdio'])
        for property in [
                'BindReadOnlyPaths=/etc/forge/codex-runtime.toml:/srv/forge/runtime/.codex/config.toml',
                'BindReadOnlyPaths=/etc/forge/empty-codex.toml:/etc/codex/config.toml',
                'InaccessiblePaths=/srv/forge/runtime/.codex/plugins',
                'InaccessiblePaths=/srv/forge/runtime/.codex/.agents/plugins']:
            self.assertIn('--property='+property, command)
        self.assertFalse(any('LoadCredential=' in part for part in command))

    def test_personal_credentials_and_project_provider_override_cannot_authorize(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            personal = root/'personal'; personal.mkdir()
            (personal/'.codex').mkdir()
            (personal/'.codex/auth.json').write_text('{"OPENAI_API_KEY":"synthetic-personal-canary"}')
            (personal/'.codex/config.toml').write_text('model_provider = "personal"\n')
            project = root/'project'; project.mkdir()
            (project/'.codex').mkdir()
            base = root/'base.toml'; base.write_text('model = "gpt-5"\n')
            empty = root/'empty.toml'; empty.write_text('')
            config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', runtime_uid=os.getuid(),
                          runtime_gid=os.getgid(), runtime_home=str(root/'runtime'),
                          agent_unit='forge-agent.service', max_lifetime_seconds=7200,
                          workspace_roots=[str(project)], env_binary='/usr/bin/env',
                          codex_binary='/opt/forge/codex', git_binary='/usr/bin/git',
                          codex_config=str(base), empty_system_config=str(empty))
            sentinels = {'HOME':str(personal), 'CODEX_HOME':str(personal/'.codex'),
                         'OPENAI_API_KEY':'synthetic-env-canary', 'CODEX_API_KEY':'synthetic-env-canary',
                         'OPENAI_BASE_URL':'http://127.0.0.1:9', 'DBUS_SESSION_BUS_ADDRESS':'synthetic-keyring'}
            command = self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                    str(project), ['/usr/bin/env'])
            launched = subprocess.check_output(command[command.index('--')+1:], env=sentinels, text=True)
            environment = dict(line.split('=',1) for line in launched.splitlines())
            self.assertEqual(environment['HOME'], str(root/'runtime'))
            self.assertEqual(environment['CODEX_HOME'], str(root/'runtime/.codex'))
            for key in ['OPENAI_API_KEY','CODEX_API_KEY','OPENAI_BASE_URL','DBUS_SESSION_BUS_ADDRESS']:
                self.assertNotIn(key, environment)
            for content in ['model_provider = "personal"\n',
                            'cli_auth_credentials_store = "keyring"\n',
                            'forced_login_method = "api"\n',
                            '[model_providers.personal]\nexperimental_bearer_token = "synthetic-project-canary"\n',
                            '[agents.worker]\nconfig_file = "personal.toml"\n']:
                (project/'.codex/config.toml').write_text(content)
                with self.subTest(content=content), patch.object(self.launcher, 'trusted_path'), patch.object(
                        self.launcher, 'validate_codex_mount_targets'), patch.object(
                        self.launcher, 'locked_receipt') as receipt, patch.object(self.launcher.subprocess, 'Popen') as launch:
                    with self.assertRaises(ValueError):
                        self.launcher.start(config, '01234567-89ab-4cde-8012-3456789abcdf', str(project),
                                            ['/opt/forge/codex', 'app-server', '--stdio'])
                    receipt.assert_not_called()
                    launch.assert_not_called()
            self.assertEqual(json.loads((personal/'.codex/auth.json').read_text()),
                             {'OPENAI_API_KEY':'synthetic-personal-canary'})

    def test_reconstructed_launch_environment_preserves_only_the_forge_profile(self):
        """Exec-environment fixture only: does not exercise host systemd or real Codex auth."""
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            personal = root/'personal'; (personal/'.codex').mkdir(parents=True)
            runtime = root/'runtime'; (runtime/'.codex').mkdir(parents=True)
            (personal/'.codex/account.fixture').write_text('terminal@example.test')
            config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', runtime_uid=os.getuid(),
                          runtime_gid=os.getgid(), runtime_home=str(runtime), agent_unit='forge-agent.service',
                          max_lifetime_seconds=7200, workspace_roots=[str(root)],
                          env_binary='/usr/bin/env', codex_binary='/synthetic/codex', git_binary='/usr/bin/git')
            reader = ('import os,pathlib; p=pathlib.Path(os.environ["CODEX_HOME"])/"account.fixture"; '
                      'print(p.read_text() if p.exists() else "SIGNED_OUT")')
            inherited = {'HOME':str(personal), 'CODEX_HOME':str(personal/'.codex'),
                         'OPENAI_API_KEY':'synthetic-terminal-key'}
            def observe():
                command = self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                            str(root), [sys.executable, '-I', '-c', reader])
                return subprocess.check_output(command[command.index('--')+1:], env=inherited, text=True).strip()
            self.assertEqual(observe(), 'SIGNED_OUT')
            account = runtime/'.codex/account.fixture'; account.write_text('forge@example.test')
            before = account.stat()
            self.assertEqual(observe(), 'forge@example.test')
            self.assertEqual(observe(), 'forge@example.test')
            self.assertEqual((account.stat().st_ino, account.stat().st_mtime_ns), (before.st_ino, before.st_mtime_ns))
            account.unlink()
            self.assertEqual(observe(), 'SIGNED_OUT')
            self.assertEqual((personal/'.codex/account.fixture').read_text(), 'terminal@example.test')

    def test_all_codex_launches_pin_forge_auth_authority(self):
        config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', runtime_uid=62002,
                      runtime_gid=62002, runtime_home='/srv/forge/runtime', agent_unit='forge-agent.service',
                      max_lifetime_seconds=7200, workspace_roots=['/srv/forge/workspaces'],
                      env_binary='/usr/bin/env', codex_binary='/opt/forge/codex', git_binary='/usr/bin/git',
                      codex_config='/etc/forge/codex-runtime.toml', empty_system_config='/etc/forge/empty-codex.toml')
        command = self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                '/srv/forge/workspaces/x', ['/opt/forge/codex', 'app-server', '--stdio'])
        expected = ['/opt/forge/codex', 'app-server', '--stdio', '-c', 'model_provider="openai"',
                    '-c', 'cli_auth_credentials_store="file"', '-c', 'forced_login_method="chatgpt"']
        self.assertEqual(command[command.index('/opt/forge/codex'):], expected)
        with tempfile.TemporaryDirectory() as folder:
            (pathlib.Path(folder)/'forge-mcp-grants').write_text('{}')
            with patch.dict(self.launcher.os.environ, {'CREDENTIALS_DIRECTORY':folder}, clear=True), patch.object(
                    self.launcher.os, 'execve') as execute:
                self.launcher.runtime_codex('/opt/forge/codex', '/srv/forge/runtime')
            self.assertEqual(execute.call_args.args[1], expected)

    @unittest.skipUnless(os.environ.get('FORGE_TEST_CODEX_BINARY'), 'opt-in installed Codex characterization')
    def test_installed_codex_cli_auth_pins_survive_a_different_project_cwd(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            home = root/'runtime'; (home/'.codex').mkdir(parents=True)
            project = root/'project'; (project/'.codex').mkdir(parents=True)
            (home/'.codex/config.toml').write_text(
                'cli_auth_credentials_store = "file"\nforced_login_method = "chatgpt"\n'
                '[projects."'+str(project)+'"]\ntrust_level = "trusted"\n')
            (project/'.codex/config.toml').write_text(
                'model_provider = "personal"\ncli_auth_credentials_store = "ephemeral"\n'
                'forced_login_method = "api"\n[model_providers.personal]\nname = "Fixture"\n'
                'base_url = "http://127.0.0.1:9"\nwire_api = "responses"\n'
                'experimental_bearer_token = "synthetic-project-canary"\nrequires_openai_auth = false\n')
            binary = os.environ['FORGE_TEST_CODEX_BINARY']
            config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', runtime_uid=os.getuid(),
                          runtime_gid=os.getgid(), runtime_home=str(home), agent_unit='forge-agent.service',
                          max_lifetime_seconds=7200, workspace_roots=[str(project)],
                          env_binary='/usr/bin/env', codex_binary=binary, git_binary='/usr/bin/git',
                          codex_config=str(root/'base.toml'), empty_system_config=str(root/'empty.toml'))
            service = self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                    str(home), [binary,'app-server','--stdio'])
            # Exercise the emitted exec/environment boundary only; no host systemd changes.
            child = subprocess.Popen(service[service.index('--')+1:], cwd=home,
                                     env={'OPENAI_API_KEY':'synthetic-parent-canary'},
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            buffered = bytearray()
            def request(identity, method, params):
                child.stdin.write((json.dumps({'id':identity,'method':method,'params':params})+'\n').encode())
                child.stdin.flush()
                deadline = time.monotonic()+10
                while time.monotonic() < deadline:
                    if b'\n' not in buffered:
                        if not select.select([child.stdout], [], [], max(0,deadline-time.monotonic()))[0]:
                            self.fail('installed Codex response timed out')
                        chunk = os.read(child.stdout.fileno(), 65536)
                        if not chunk: self.fail('installed Codex closed its output')
                        buffered.extend(chunk)
                    while b'\n' in buffered:
                        line, _, remaining = buffered.partition(b'\n'); buffered[:] = remaining
                        response = json.loads(line)
                        if response.get('id') == identity:
                            self.assertNotIn('error', response)
                            return response['result']
                self.fail('installed Codex response timed out')
            try:
                initialized = request(1, 'initialize', {'clientInfo':{'name':'forge_isolation_test','version':'0'}})
                self.assertRegex(initialized['userAgent'], r'/0\.160\.[01] ')
                child.stdin.write(b'{"method":"initialized"}\n'); child.stdin.flush()
                response = request(2, 'config/read', {'cwd':str(project),'includeLayers':True})
                self.assertTrue(any(layer['name']['type']=='project' and not layer.get('disabledReason')
                                    for layer in response['layers']))
                self.assertEqual(response['config']['model_provider'], 'openai')
                self.assertEqual(response['config']['cli_auth_credentials_store'], 'file')
                self.assertEqual(response['config']['forced_login_method'], 'chatgpt')
                account = request(3, 'account/read', {'refreshToken':False})
                self.assertIsNone(account['account'])
                self.assertTrue(account['requiresOpenaiAuth'])
            finally:
                child.terminate()
                try: child.wait(timeout=5)
                except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=5)
                child.stdin.close(); child.stdout.close()

    def test_base_config_rejects_auth_sources_and_unknown_nested_configuration(self):
        with tempfile.TemporaryDirectory() as folder:
            base = pathlib.Path(folder)/'base.toml'; empty = pathlib.Path(folder)/'empty.toml'
            empty.write_text('')
            profile = {'codex_config':str(base), 'empty_system_config':str(empty)}
            with patch.object(self.launcher, 'trusted_path'):
                for content in ['model_provider = "custom"\n',
                                'cli_auth_credentials_store = "auto"\n',
                                'forced_login_method = "api"\n',
                                '[profiles.worker]\nmodel_provider = "custom"\n',
                                '[agents.worker]\nconfig_file = "/synthetic/other.toml"\n',
                                'unknown_future_auth_setting = "custom"\n']:
                    base.write_text(content)
                    with self.subTest(content=content), self.assertRaisesRegex(ValueError, 'unsafe Codex base'):
                        self.launcher.validate_codex_isolation_config(profile)

    def test_project_config_accepts_harmless_settings_but_rejects_nested_auth_and_symlinks(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder); project = root/'project'; project.mkdir()
            (root/'.codex').mkdir(); (project/'.codex').mkdir()
            config_file = root/'.codex/config.toml'
            config_file.write_text('model = "gpt-5"\nmodel_reasoning_effort = "high"\n')
            self.launcher.validate_codex_project_config(str(project))
            for content in ['[profiles.worker]\ncli_auth_credentials_store = "keyring"\n',
                            '[agents.worker]\nmodel_provider = "personal"\n',
                            '[agents.worker]\nconfig_file = "personal.toml"\n',
                            '[model_providers.personal.auth]\ncommand = "/synthetic/token"\n']:
                config_file.write_text(content)
                with self.subTest(content=content), self.assertRaises(ValueError):
                    self.launcher.validate_codex_project_config(str(project))
            config_file.unlink(); config_file.symlink_to(root/'outside.toml')
            with self.assertRaises(ValueError):
                self.launcher.validate_codex_project_config(str(project))

    def test_legacy_profile_denies_codex_but_remains_usable_for_non_codex_launches(self):
        config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', control_uid=2001,
                      runtime_uid=2002, runtime_gid=2002, runtime_home='/srv/forge/runtime',
                      workspace_roots=['/srv/forge/workspaces'], agent_unit='forge-agent.service',
                      max_lifetime_seconds=7200, codex_binary='/opt/forge/codex',
                      git_binary='/usr/bin/git', env_binary='/usr/bin/env')
        self.assertFalse(self.launcher.has_codex_isolation_config(config))
        execution='01234567-89ab-4cde-8012-3456789abcdf'
        with patch.object(self.launcher, 'load_config', return_value=config), patch.object(
                self.launcher, 'caller'), patch.object(self.launcher, 'workspace', return_value='/srv/forge/workspaces/x'), patch.object(
                self.launcher, 'locked_receipt') as receipt:
            with self.assertRaisesRegex(ValueError, 'Codex isolation configuration unavailable'):
                self.launcher.main(['start','codex',execution,'/srv/forge/workspaces/x'])
            receipt.assert_not_called()
        command = self.launcher.service_command(config, execution, '/srv/forge/workspaces/x', ['/usr/bin/git','status'])
        self.assertIn('/usr/bin/git',command)

    def test_mcp_codex_start_requires_complete_isolation_config(self):
        config = dict(installation='01234567-89ab-4cde-8012-3456789abcde', control_uid=2001,
                      runtime_uid=2002, runtime_gid=2002, runtime_home='/srv/forge/runtime',
                      workspace_roots=['/srv/forge/workspaces'], agent_unit='forge-agent.service',
                      max_lifetime_seconds=7200, codex_binary='/opt/forge/codex',
                      git_binary='/usr/bin/git', env_binary='/usr/bin/env')
        with self.assertRaises(ValueError):
            self.launcher.has_codex_isolation_config(dict(config, codex_config='/etc/forge/base.toml'))
        with self.assertRaises(ValueError):
            self.launcher.service_command(config, '01234567-89ab-4cde-8012-3456789abcdf',
                    '/srv/forge/workspaces/x', ['/opt/forge/codex'], '/run/forge-runtime/grant')
        with self.assertRaises((ValueError, FileNotFoundError)):
            self.launcher.validate_codex_isolation_config(dict(config,
                    codex_config='/etc/forge/nonexistent-stage4-base.toml',
                    empty_system_config='/etc/forge/nonexistent-stage4-empty.toml'))
        with patch.object(self.launcher, 'locked_receipt') as receipt:
            with self.assertRaises((ValueError, FileNotFoundError)):
                self.launcher.start(dict(config,
                        codex_config='/etc/forge/nonexistent-stage4-base.toml',
                        empty_system_config='/etc/forge/nonexistent-stage4-empty.toml'),
                        '01234567-89ab-4cde-8012-3456789abcdf', '/srv/forge/workspaces/x',
                        ['/opt/forge/codex'], codex_grants={})
            receipt.assert_not_called()

    def test_explicit_mcp_start_reads_grants_before_isolated_launch(self):
        config = {'codex_binary':'/opt/forge/codex', 'codex_config':'/etc/forge/base.toml',
                  'empty_system_config':'/etc/forge/empty.toml'}
        execution='01234567-89ab-4cde-8012-3456789abcdf'
        grants={'FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF':'synthetic-grant'}
        with patch.object(self.launcher, 'load_config', return_value=config), patch.object(
                self.launcher, 'caller'), patch.object(self.launcher, 'workspace', return_value='/srv/forge/workspaces/x'), patch.object(
                self.launcher, 'start', return_value=0) as start, patch.object(
                self.launcher, 'read_startup_envelope', return_value=grants) as read, patch.object(
                self.launcher.os, 'dup', return_value=7), patch.object(
                self.launcher.os, 'fdopen', return_value=io.BytesIO(b'{}\n')):
            self.assertEqual(self.launcher.main(['start','codex',execution,'/srv/forge/workspaces/x','--mcp']),0)
        read.assert_called_once()
        start.assert_called_once_with(config, execution, '/srv/forge/workspaces/x',
                                      ['/opt/forge/codex','app-server','--stdio'], codex_grants=grants)

    def test_startup_envelope_rejects_unsafe_names_and_values_before_launch(self):
        valid=b'{"FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF":"synthetic-grant"}\n'
        self.assertEqual(self.launcher.read_startup_envelope(io.BytesIO(valid)),
                         {'FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF':'synthetic-grant'})
        for payload in [b'{"PATH":"synthetic-grant"}\n', b'{"FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF":"a\\nb"}\n',
                        b'{}' + b' ' * 262145 + b'\n', b'[]\n']:
            with self.subTest(payload=payload[:30]), self.assertRaises(ValueError):
                self.launcher.read_startup_envelope(io.BytesIO(payload))

    def test_credential_source_is_private_distinct_and_removed_by_stop(self):
        with tempfile.TemporaryDirectory() as folder:
            installation='01234567-89ab-4cde-8012-3456789abcde'
            first='01234567-89ab-4cde-8012-3456789abcdf'
            second='01234567-89ab-4cde-8012-3456789abce0'
            base=pathlib.Path(folder)
            owned=base/installation; owned.mkdir(mode=0o700)
            config={'installation':installation}
            grants={'FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF':'synthetic-grant'}
            with patch.object(self.launcher, 'RECEIPTS', base), patch.object(
                    self.launcher, 'receipt_directory', return_value=owned):
                source_a=self.launcher.write_credential(config,first,grants)
                source_b=self.launcher.write_credential(config,second,grants)
                self.assertNotEqual(source_a,source_b)
                self.assertEqual(source_a.stat().st_mode & 0o777,0o600)
                self.assertIn('synthetic-grant',source_a.read_text())
                with patch.object(self.launcher,'locked_receipt',return_value=io.StringIO('{"state":"starting"}')), patch.object(
                        self.launcher,'stop_unit'):
                    self.launcher.stop(config,first)
                self.assertFalse(source_a.exists())
                self.assertTrue(source_b.exists())

    def test_failed_credential_write_removes_partial_private_source(self):
        with tempfile.TemporaryDirectory() as folder:
            installation='01234567-89ab-4cde-8012-3456789abcde'
            execution='01234567-89ab-4cde-8012-3456789abcdf'
            base=pathlib.Path(folder)
            owned=base/installation; owned.mkdir(mode=0o700)
            config={'installation':installation}
            grants={'FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF':'synthetic-grant'}
            with patch.object(self.launcher, 'RECEIPTS', base), patch.object(
                    self.launcher, 'receipt_directory', return_value=owned), patch.object(
                    self.launcher.os, 'write', side_effect=OSError(errno.EIO,'synthetic failure')):
                with self.assertRaises(OSError):
                    self.launcher.write_credential(config,execution,grants)
                self.assertFalse((owned/(execution+'.credential')).exists())

    def test_runtime_bootstrap_discards_inherited_environment_after_reading_credential(self):
        with tempfile.TemporaryDirectory() as folder:
            directory=pathlib.Path(folder)
            (directory/'forge-mcp-grants').write_text(
                '{"FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF":"synthetic-grant"}')
            environment={'CREDENTIALS_DIRECTORY':folder, 'PARENT_SECRET':'synthetic-parent-canary',
                         'HOME':'/srv/forge/runtime', 'CODEX_HOME':'/srv/forge/runtime/.codex'}
            with patch.dict(self.launcher.os.environ, environment, clear=True), patch.object(
                    self.launcher.os, 'execve') as execute:
                self.launcher.runtime_codex('/opt/forge/codex', '/srv/forge/runtime')
            passed=execute.call_args.args[2]
            self.assertEqual(passed['FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF'], 'synthetic-grant')
            self.assertEqual(passed['CODEX_HOME'], '/srv/forge/runtime/.codex')
            self.assertNotIn('PARENT_SECRET', passed)
            self.assertNotIn('CREDENTIALS_DIRECTORY', passed)

    def test_codex_mount_targets_reject_symlinked_config_and_plugin_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            home=pathlib.Path(folder)
            codex=home/'.codex'; codex.mkdir()
            config_file=codex/'config.toml'; config_file.write_text('')
            plugins=codex/'plugins'; plugins.mkdir()
            agent_plugins=codex/'.agents/plugins'; agent_plugins.mkdir(parents=True)
            for path in (codex, config_file, plugins, codex/'.agents', agent_plugins):
                path.chmod(0o600 if path == config_file else 0o700)
            profile={'runtime_home':str(home), 'runtime_uid':os.getuid()}
            self.launcher.validate_codex_mount_targets(profile)
            config_file.unlink(); config_file.symlink_to('/etc/passwd')
            with self.assertRaises(ValueError): self.launcher.validate_codex_mount_targets(profile)
            config_file.unlink(); config_file.write_text('')
            plugins.rmdir(); plugins.symlink_to('/tmp')
            with self.assertRaises(ValueError): self.launcher.validate_codex_mount_targets(profile)

    def test_runtime_probe_rejects_readable_synthetic_control_file(self):
        if not SOURCE.exists(): self.skipTest('implementation absent')
        with tempfile.TemporaryDirectory() as folder:
            secret = pathlib.Path(folder)/'synthetic'; secret.write_text('synthetic-only')
            report = self.launcher.runtime_report({'protected_paths':[str(secret)], 'control_pid':1})
            self.assertFalse(report['protected_denied'])
            self.assertNotIn('synthetic-only', str(report))

    def test_read_only_filesystem_write_is_denied_but_unexpected_io_error_fails(self):
        with patch.object(self.launcher.os, 'open', side_effect=OSError(errno.EROFS, 'read-only')):
            self.assertTrue(self.launcher.denied('/synthetic/protected', self.launcher.os.O_WRONLY))
        with patch.object(self.launcher.os, 'open', side_effect=OSError(errno.EIO, 'unexpected')):
            with self.assertRaises(OSError):
                self.launcher.denied('/synthetic/protected', self.launcher.os.O_WRONLY)

    def test_stop_requires_positive_cgroup_empty_proof(self):
        if not SOURCE.exists(): self.skipTest('implementation absent')
        from unittest.mock import patch
        import subprocess
        config = {'installation':'01234567-89ab-4cde-8012-3456789abcde'}
        unit = '01234567-89ab-4cde-8012-3456789abcdf'
        state = {'LoadState':'loaded','ActiveState':'inactive','SubState':'dead','MainPID':'0','ControlGroup':'/owned'}
        with patch.object(self.launcher, 'state', return_value=state), patch.object(self.launcher, 'control', return_value=subprocess.CompletedProcess([],0)), patch.object(self.launcher, 'cgroup_empty', return_value=False):
            with self.assertRaises(ValueError): self.launcher.stop_unit(config,unit)

    def test_systemd_specifier_working_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            cwd=pathlib.Path(folder)/'%h'; cwd.mkdir()
            with self.assertRaises(ValueError): self.launcher.workspace({'workspace_roots':[folder]},str(cwd))

    def test_neutral_codex_directory_is_managed_but_outside_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder) / 'forge-projects'
            neutral = root / '.forge-codex-runtime'
            neutral.mkdir(parents=True)
            outside = pathlib.Path(folder) / 'outside'
            outside.mkdir()
            config = {'workspace_roots': [str(root)]}
            self.assertEqual(self.launcher.workspace(config, str(neutral)), str(neutral))
            with self.assertRaisesRegex(ValueError, 'unmanaged working directory'):
                self.launcher.workspace(config, str(outside))

    def test_failed_start_inspection_still_attempts_owned_cleanup(self):
        from unittest.mock import patch, MagicMock
        import io
        receipt=io.StringIO()
        child=MagicMock(); child.poll.return_value=1
        with patch.object(self.launcher, 'locked_receipt', return_value=receipt), patch.object(self.launcher.subprocess,'Popen',return_value=child), patch.object(self.launcher,'service_command',return_value=['fixed']), patch.object(self.launcher,'unit_name',return_value='owned'), patch.object(self.launcher,'state',side_effect=ValueError('unavailable')), patch.object(self.launcher,'stop') as stop:
            with self.assertRaises(ValueError): self.launcher.start({},'unused','/',['fixed'])
            stop.assert_called_once_with({},'unused')

    def test_git_trust_is_limited_to_canonical_managed_C_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            config={'workspace_roots':[folder], 'git_binary':'/usr/bin/git'}
            command=self.launcher.git_command(config,['-C',folder,'status'])
            self.assertEqual(['/usr/bin/git','-c','safe.directory='+folder,'-C',folder,'status'],command)
            with self.assertRaises(ValueError): self.launcher.git_command(config,['-C','/','status'])
            self.assertNotIn('safe.directory=*',command)

    def test_partial_receipt_from_crash_still_reconciles_owned_unit(self):
        from unittest.mock import patch
        import io
        receipt=io.StringIO('{"state":')
        with tempfile.TemporaryDirectory() as directory, patch.object(self.launcher,'locked_receipt',return_value=receipt), patch.object(self.launcher,'stop_unit') as stop, patch.object(self.launcher,'credential_path',return_value=pathlib.Path(directory)/'grant'):
            config={'installation':'01234567-89ab-4cde-8012-3456789abcde'}
            execution='01234567-89ab-4cde-8012-3456789abcdf'
            self.launcher.stop(config,execution)
            stop.assert_called_once_with(config,execution)

    def test_root_file_rejects_symlink_and_writable_ancestor(self):
        if not SOURCE.exists(): self.skipTest('implementation absent')
        with tempfile.TemporaryDirectory() as folder:
            path=pathlib.Path(folder)/'config'; path.write_text('{}')
            with self.assertRaises(ValueError): self.launcher.trusted_path(path)
            link=pathlib.Path(folder)/'link'; link.symlink_to(path)
            with self.assertRaises(ValueError): self.launcher.trusted_path(link)

if __name__ == '__main__': unittest.main()
