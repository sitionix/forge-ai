"""Regression checks for Forge's Remote Access systemd preparation."""

import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/remote-access'))


class RemoteAccessStartupTest(unittest.TestCase):
    def test_existing_control_directory_becomes_traversable_without_listing(self):
        import install
        with tempfile.TemporaryDirectory() as directory:
            control = pathlib.Path(directory) / 'forge-remote'
            control.mkdir(mode=0o700)
            control.chmod(0o700)
            with (mock.patch.object(install, 'ETC', control),
                  mock.patch.object(install, 'require_root_owned')):
                install.ensure_control_directory()
            self.assertEqual(0o711, control.stat().st_mode & 0o777)

    def test_missing_system_sshd_is_installed_on_demand_without_masking_ssh(self):
        import prepare_startup
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv,
                3 if argv[:3] == ['/usr/bin/systemctl', 'is-active', '--quiet'] else 0,
                stdout='not-found\n')

        with (mock.patch.object(prepare_startup, 'SSHD', pathlib.Path('/missing/sshd')),
              mock.patch.object(prepare_startup, 'is_debian_host', return_value=True),
              mock.patch.object(prepare_startup, 'sshd_available', side_effect=[False, True]),
              mock.patch('subprocess.run', side_effect=run)):
            prepare_startup.ensure_openssh_server()

        self.assertIn(['/usr/bin/apt-get', 'install', '-y', '--no-install-recommends', 'openssh-server'], calls)
        self.assertFalse(any(command[:2] == ['/usr/bin/systemctl', 'mask'] for command in calls))
        self.assertFalse(any(command[:2] == ['/usr/bin/systemctl', 'disable'] for command in calls))

    def test_failed_system_sshd_install_does_not_mask_existing_ssh(self):
        import prepare_startup
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            if argv[:2] == ['/usr/bin/apt-get', 'install']:
                raise subprocess.CalledProcessError(1, argv)
            return subprocess.CompletedProcess(argv, 0, stdout='not-found\n')

        with (mock.patch.object(prepare_startup, 'is_debian_host', return_value=True),
              mock.patch.object(prepare_startup, 'sshd_available', return_value=False),
              mock.patch('subprocess.run', side_effect=run)):
            with self.assertRaisesRegex(RuntimeError, 'REMOTE_ACCESS_SSHD_NOT_READY'):
                prepare_startup.ensure_openssh_server()
        self.assertFalse(any(command and command[0] == '/usr/bin/systemctl' for command in calls))

    def test_workload_installer_exposes_preparation_helper(self):
        import install as managed_ssh
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with (mock.patch.object(managed_ssh, 'LIB', root / 'lib'),
                  mock.patch.object(managed_ssh, 'STATE', root / 'state'),
                  mock.patch.object(managed_ssh, 'ETC', root / 'etc'),
                  mock.patch.object(managed_ssh, 'directory'),
                  mock.patch.object(managed_ssh, 'require_root_owned'),
                  mock.patch.object(managed_ssh, 'install_managed_helper') as install_helper,
                  mock.patch.object(managed_ssh, 'write_owned')):
                managed_ssh.install_workloads(root)
            installed_names = [call.args[1] for call in install_helper.call_args_list]
            self.assertIn('prepare-workspace', installed_names)

    def test_selected_endpoint_is_persisted_without_silent_replacement(self):
        import prepare_startup
        with tempfile.TemporaryDirectory() as directory:
            endpoint=pathlib.Path(directory) / 'endpoint.env'
            prepare_startup.write_endpoint_env(endpoint,'192.168.2.5',os.getuid())
            prepare_startup.write_endpoint_env(endpoint,'192.168.2.5',os.getuid())
            self.assertEqual(endpoint.read_text(),
                             'FORGE_AGENT_REMOTE_ACCESS_ADVERTISED_HOST=192.168.2.5\n')
            self.assertEqual(endpoint.stat().st_mode & 0o777,0o600)
            with self.assertRaisesRegex(RuntimeError,'REMOTE_ACCESS_ENDPOINT_CONFLICT'):
                prepare_startup.write_endpoint_env(endpoint,'192.168.2.6',os.getuid())
            self.assertEqual(endpoint.read_text(),
                             'FORGE_AGENT_REMOTE_ACCESS_ADVERTISED_HOST=192.168.2.5\n')
    def test_renderer_adds_dedicated_loopback_services_without_changing_ordinary_units(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory)
            environment = os.environ.copy()
            environment.update(FORGE_SYSTEMD_USER='local-operator',
                               FORGE_SYSTEMD_GROUP='local-operator')
            subprocess.run([str(ROOT / 'scripts/systemd/render-units.sh'),
                            str(target / 'units'), str(target / 'forge-ai.env'),
                            '/etc/forge-ai/forge-ai.env'],
                           cwd=ROOT, env=environment, check=True, capture_output=True, text=True)

            ordinary_agent = (target / 'units/forge-agent.service').read_text()
            ordinary_nexus = (target / 'units/forge-nexus.service').read_text()
            remote_agent_path = target / 'units/forge-remote-agent.service'
            remote_nexus_path = target / 'units/forge-remote-nexus.service'

            self.assertTrue(remote_agent_path.is_file(), 'dedicated Agent unit must be rendered')
            self.assertTrue(remote_nexus_path.is_file(), 'dedicated Nexus unit must be rendered')
            remote_agent = remote_agent_path.read_text()
            remote_nexus = remote_nexus_path.read_text()

            self.assertIn('User=local-operator', ordinary_agent)
            self.assertIn('User=local-operator', ordinary_nexus)
            self.assertNotIn('remote-access/management', ordinary_agent)
            self.assertNotIn('remote-access/management', ordinary_nexus)
            self.assertIn('User=forge-control', remote_agent)
            self.assertIn('/etc/forge-remote/management/agent.env', remote_agent)
            self.assertIn('127.0.0.1', remote_agent)
            self.assertIn('PrivateTmp=yes', remote_agent)
            self.assertIn('/etc/forge-remote/management/nexus.env', remote_nexus)
            self.assertIn('127.0.0.1', remote_nexus)
            bootstrap_socket = (target / 'units/forge-remote-bootstrap.socket').read_text()
            bootstrap_service = (target / 'units/forge-remote-bootstrap.service').read_text()
            enable_service = (target / 'units/forge-remote-setup.service').read_text()
            self.assertIn('SocketMode=0600', bootstrap_socket)
            self.assertIn('SocketUser=local-operator', bootstrap_socket)
            self.assertIn('/usr/libexec/forge-remote/bootstrap.py local-operator', bootstrap_service)
            self.assertIn('/usr/libexec/forge-remote/prepare-enable.py run local-operator', enable_service)

    def test_installer_places_inert_remote_units_before_enable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            agent_jar = root / 'agent.jar'
            agent_jar.write_bytes(b'synthetic-agent')
            environment = os.environ.copy()
            environment.update(FORGE_SYSTEMD_USER='local-operator',
                               FORGE_SYSTEMD_GROUP='local-operator',
                               FORGE_SYSTEMD_USE_SUDO='0',
                               FORGE_SYSTEMD_SKIP_RELOAD='1',
                               FORGE_SYSTEMD_UNIT_DIR=str(root / 'systemd'),
                               FORGE_SYSTEMD_ENV_DIR=str(root / 'etc'),
                               FORGE_SYSTEMD_ENV_FILE=str(root / 'etc/forge-ai.env'),
                               FORGE_REMOTE_ACCESS_AGENT_JAR_SOURCE=str(agent_jar),
                               FORGE_REMOTE_BOOTSTRAP_BIN_DIR=str(root / 'bootstrap-bin'),
                               FORGE_REMOTE_BOOTSTRAP_MANIFEST=str(root / 'etc/enable.json'))
            subprocess.run([str(ROOT / 'scripts/systemd/install.sh')], cwd=ROOT,
                           env=environment, check=True, capture_output=True, text=True)
            self.assertTrue((root / 'systemd/forge-agent.service').is_file())
            self.assertTrue((root / 'systemd/forge-nexus.service').is_file())
            self.assertTrue((root / 'systemd/forge-remote-agent.service').is_file())
            self.assertTrue((root / 'systemd/forge-remote-nexus.service').is_file())
            control_agent = root / 'etc/forge-remote-agent.env'
            control_nexus = root / 'etc/forge-remote-nexus.env'
            self.assertTrue(control_agent.is_file())
            self.assertTrue(control_nexus.is_file())
            self.assertEqual(0o600, control_agent.stat().st_mode & 0o777)
            self.assertEqual(0o600, control_nexus.stat().st_mode & 0o777)
            self.assertTrue((root / 'systemd/forge-remote-bootstrap.socket').is_file())
            self.assertTrue((root / 'systemd/forge-remote-bootstrap.service').is_file())
            self.assertTrue((root / 'systemd/forge-remote-setup.service').is_file())
            self.assertTrue((root / 'bootstrap-bin/bootstrap.py').is_file())
            self.assertTrue((root / 'bootstrap-bin/prepare-enable.py').is_file())
            manifest = root / 'etc/enable.json'
            self.assertEqual(0o600, manifest.stat().st_mode & 0o777)
            self.assertIn('agentJarPath', manifest.read_text())

    def test_control_agent_uses_separate_database_and_no_codex_command(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory)
            environment = os.environ.copy()
            environment.update(FORGE_AGENT_DB_USERNAME='test-user',
                               FORGE_AGENT_DB_PASSWORD='synthetic-test-secret',
                               FORGE_AGENT_CODEX_COMMAND='should-not-cross-boundary')
            subprocess.run([str(ROOT / 'scripts/systemd/render-units.sh'),
                            str(target / 'units'), str(target / 'forge-ai.env'),
                            '/etc/forge-ai/forge-ai.env'],
                           cwd=ROOT, env=environment, check=True, capture_output=True, text=True)
            control_path = target / 'units/control-agent.env'
            self.assertTrue(control_path.is_file(), 'dedicated Agent environment must exist')
            control_env = control_path.read_text()
            self.assertIn('/forge_remote_access', control_env)
            self.assertIn('FORGE_AGENT_DB_USERNAME="test-user"', control_env)
            self.assertIn('FORGE_AGENT_DB_PASSWORD="synthetic-test-secret"', control_env)
            self.assertNotIn('FORGE_AGENT_CODEX_COMMAND', control_env)
            self.assertNotIn('should-not-cross-boundary', control_env)

    def test_address_selection_uses_single_default_route_without_guessing(self):
        self.assertTrue((ROOT / 'scripts/remote-access/prepare_startup.py').is_file(),
                        'startup address selector must exist')
        import prepare_startup

        routes = [dict(dst='default', dev='wlp0', prefsrc='192.168.2.5')]
        addresses = [dict(ifname='wlp0', addr_info=[dict(family='inet', local='192.168.2.5')]),
                     dict(ifname='docker0', addr_info=[dict(family='inet', local='172.18.0.1')])]
        self.assertEqual(prepare_startup.select_address(routes, addresses, None), '192.168.2.5')
        with self.assertRaisesRegex(ValueError, 'REMOTE_ACCESS_ADDRESS_REQUIRED'):
            prepare_startup.select_address(routes + [dict(dst='default', dev='enp0', prefsrc='10.0.0.5')],
                                           addresses + [dict(ifname='enp0', addr_info=[dict(family='inet', local='10.0.0.5')])], None)
        with self.assertRaisesRegex(ValueError, 'REMOTE_ACCESS_ADDRESS_REQUIRED'):
            prepare_startup.select_address(routes, addresses, '0.0.0.0')

    def test_dedicated_database_creation_is_idempotent_and_does_not_log_password(self):
        import prepare_startup
        self.assertTrue(hasattr(prepare_startup, 'ensure_database'), 'database preparation must exist')

        requests = []
        passwords = []

        def fake_run(argv, **kwargs):
            requests.append(argv)
            passwords.append(kwargs['env']['PGPASSWORD'])
            return subprocess.CompletedProcess(argv, 0, stdout='1\n' if len(requests) > 1 else '')

        with mock.patch('subprocess.run', side_effect=fake_run):
            prepare_startup.ensure_database('jdbc:postgresql://127.0.0.1:54329/forge_remote_access',
                                            'forge_agent', 'synthetic-test-secret')

        self.assertEqual(len(requests), 2)
        self.assertIn('CREATE DATABASE forge_remote_access', requests[1])
        self.assertNotIn('synthetic-test-secret', repr(requests))
        self.assertEqual(passwords, ['synthetic-test-secret', 'synthetic-test-secret'])

        requests.clear()
        with mock.patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, stdout='1\n')) as existing:
            prepare_startup.ensure_database('jdbc:postgresql://127.0.0.1:54329/forge_remote_access',
                                            'forge_agent', 'synthetic-test-secret')
        existing.assert_called_once()

        with self.assertRaisesRegex(ValueError, 'REMOTE_ACCESS_DB_URL_INVALID'):
            prepare_startup.ensure_database('jdbc:postgresql://127.0.0.1:54329/forge_agent',
                                            'forge_agent', 'synthetic-test-secret')

    def test_protected_environment_parser_rejects_unexpected_keys(self):
        import prepare_startup
        self.assertTrue(hasattr(prepare_startup, 'read_control_env'))
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'control.env'
            path.write_text('FORGE_AGENT_DB_URL="jdbc:postgresql://localhost:54329/forge_remote_access"\n'
                            'FORGE_AGENT_DB_USERNAME="forge_agent"\n'
                            'FORGE_AGENT_DB_PASSWORD="synthetic-secret"\n')
            path.chmod(0o600)
            values = prepare_startup.read_control_env(path)
            self.assertEqual(values['FORGE_AGENT_DB_PASSWORD'], 'synthetic-secret')
            path.write_text(path.read_text() + 'FORGE_AGENT_CODEX_COMMAND="danger"\n')
            with self.assertRaisesRegex(ValueError, 'REMOTE_ACCESS_ENV_INVALID'):
                prepare_startup.read_control_env(path)

    def test_stage_copies_reviewed_package_and_agent_jar_to_protected_location(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            test_jar = root / 'agent.jar'
            test_jar.write_bytes(b'synthetic-test-jar')
            environment = os.environ.copy()
            environment.update(FORGE_SYSTEMD_USE_SUDO='0',
                               FORGE_REMOTE_ACCESS_PACKAGE_DIR=str(root / 'package'),
                               FORGE_REMOTE_ACCESS_JAR_DIR=str(root / 'jars'),
                               FORGE_REMOTE_ACCESS_AGENT_JAR_SOURCE=str(test_jar))
            staged = ROOT / 'scripts/runtime/stage-remote-access.sh'
            self.assertTrue(staged.is_file(), 'startup package staging script must exist')
            subprocess.run([str(staged)], cwd=ROOT, env=environment,
                           check=True, capture_output=True, text=True)
            for name in ('install.py', 'prepare_startup.py', 'prepare_management.py',
                         'prepare_rootfs.py', 'rootfs.Dockerfile',
                         'prepare_local_exec.py', 'forced_command.py',
                         'workload_supervisor.py', 'execution_channel.py'):
                self.assertEqual((root / 'package' / name).read_bytes(),
                                 (ROOT / 'scripts/remote-access' / name).read_bytes())
            self.assertTrue((root / 'jars/forge-agent.jar').is_file())
            self.assertEqual((root / 'jars/forge-agent.jar').stat().st_mode & 0o777, 0o644)

    def test_startup_prepares_database_and_management_before_claiming_ready(self):
        import prepare_startup
        self.assertTrue(hasattr(prepare_startup, 'prepare_services'))
        with tempfile.TemporaryDirectory() as directory:
            package = pathlib.Path(directory)
            recorded = []

            def fake_run(argv, **kwargs):
                recorded.append(argv)
                return subprocess.CompletedProcess(argv, 0, stdout='')

            with (mock.patch.object(prepare_startup, 'ensure_database') as database,
                  mock.patch.object(prepare_startup, 'ensure_openssh_server') as sshd,
                  mock.patch('subprocess.run', side_effect=fake_run)):
                prepare_startup.prepare_services(package, 'local-operator', '192.168.2.5',
                                                 'jdbc:postgresql://127.0.0.1:54329/forge_remote_access',
                                                 'forge_agent', 'synthetic-secret')
            database.assert_called_once()
            sshd.assert_called_once()
            self.assertEqual([pathlib.Path(call[2]).name for call in recorded],
                             ['install.py', 'prepare_management.py', 'prepare_local_exec.py'])
            self.assertNotIn('synthetic-secret', repr(recorded))

    def test_repeat_setup_never_controls_the_system_ssh_service(self):
        import prepare_startup
        with tempfile.TemporaryDirectory() as directory:
            package = pathlib.Path(directory)
            with (mock.patch.object(prepare_startup, 'ensure_database'),
                  mock.patch.object(prepare_startup, 'ensure_openssh_server'),
                  mock.patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0)) as commands):
                prepare_startup.prepare_services(package, 'local-operator', '192.168.2.5',
                                                 'jdbc:postgresql://127.0.0.1:54329/forge_remote_access',
                                                 'forge_agent', 'synthetic-secret')
            self.assertIn('install.py', repr(commands.call_args_list))
            self.assertNotIn('systemctl', repr(commands.call_args_list))

    def test_startup_cli_refuses_unprivileged_execution_without_side_effects(self):
        result = subprocess.run(['/usr/bin/python3', '-I',
                                 str(ROOT / 'scripts/remote-access/prepare_startup.py'),
                                 '--operator-user', 'local-operator'],
                                cwd=ROOT, capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('REMOTE_ACCESS_REQUIRES_ROOT', result.stderr)
        self.assertNotIn('REMOTE_ACCESS_PREPARED', result.stdout)

    def test_just_start_does_not_prepare_or_start_remote_access(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            binaries = root / 'bin'
            binaries.mkdir()
            journal = root / 'calls.txt'
            scripts = {
                'sudo': '#!/bin/sh\necho "sudo $*" >> "$FORGE_TEST_CALLS"\n'
                        'case "$*" in *prepare_startup.py*|*"/usr/local/lib/forge-remote"*) exit 0;; esac\nexec "$@"\n',
                'systemctl': '#!/bin/sh\necho "systemctl $*" >> "$FORGE_TEST_CALLS"\n'
                             'case "$1" in show) case "$*" in *Version*) echo 255;; *) echo loaded;; esac;; '
                             'is-active) case "$*" in *forge-remote-agent.service*) echo inactive; exit 3;; *) echo active;; esac;; esac\n',
                'docker': '#!/bin/sh\necho "docker $*" >> "$FORGE_TEST_CALLS"\n'
                          'case "$*" in *ps*) echo container-id;; esac\n',
                'curl': '#!/bin/sh\nexit 0\n',
            }
            for name, body in scripts.items():
                path = binaries / name
                path.write_text(body)
                path.chmod(0o755)
            jar = root / 'agent.jar'
            jar.write_bytes(b'synthetic-test-jar')
            runtime = root / 'systemd-runtime'
            runtime.mkdir()
            environment = os.environ.copy()
            environment.update(PATH=f'{binaries}:{environment.get("PATH", "")}',
                               FORGE_TEST_CALLS=str(journal),
                               FORGE_SYSTEMD_USE_SUDO='0',
                               FORGE_SYSTEMD_SKIP_RELOAD='1',
                               FORGE_SYSTEMD_RUNTIME_DIR=str(runtime),
                               FORGE_SYSTEMD_UNIT_DIR=str(root / 'units'),
                               FORGE_SYSTEMD_ENV_DIR=str(root / 'etc'),
                               FORGE_SYSTEMD_ENV_FILE=str(root / 'etc/forge-ai.env'),
                               FORGE_RUNTIME_PREPARE_COMMAND='/bin/true',
                               FORGE_REMOTE_ACCESS_AGENT_JAR_SOURCE=str(jar),
                               FORGE_REMOTE_ACCESS_PACKAGE_DIR=str(root / 'package'),
                               FORGE_REMOTE_ACCESS_JAR_DIR=str(root / 'jars'),
                               FORGE_REMOTE_BOOTSTRAP_BIN_DIR=str(root / 'bootstrap-bin'),
                               FORGE_REMOTE_BOOTSTRAP_MANIFEST=str(root / 'etc/enable.json'),
                               FORGE_RUNTIME_HEALTH_ATTEMPTS='1')
            result = subprocess.run([str(ROOT / 'scripts/runtime/systemd.sh'), 'start'],
                                    cwd=ROOT, env=environment, capture_output=True,
                                    text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = journal.read_text()
            self.assertNotIn('prepare_startup.py', calls)
            starts = '\n'.join(line for line in calls.splitlines()
                               if 'systemctl start ' in line or 'systemctl restart ' in line)
            self.assertNotIn('forge-remote-agent.service', starts)
            self.assertNotIn('forge-remote-nexus.service', starts)
            self.assertNotIn('forge-remote-sshd.service', starts)
            self.assertNotIn('REMOTE_ACCESS_PACKAGE_STAGED', result.stdout)
            self.assertNotIn('REMOTE_ACCESS_PREPARED', result.stdout)
            self.assertIn('forge-agent.service', calls)
            self.assertIn('forge-nexus.service', calls)
            self.assertIn('systemctl enable --now forge-remote-bootstrap.socket', calls)


if __name__ == '__main__':
    unittest.main()
