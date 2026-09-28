"""Protected material contracts; not systemd isolation evidence."""
import base64
import importlib.util
import os
import pathlib
import stat
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).parents[1] / 'prepare_mcp.py'


class McpProvisioningTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('prepare_mcp', SOURCE)
        self.provision = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.provision)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name) / 'mcp'

    def prepare(self):
        return self.provision.prepare(self.root, os.getuid(), os.getgid(),
                                      b'synthetic-db-password', 'http://127.0.0.1:9099')

    def test_protected_distinct_material_and_no_secret_environment_values(self):
        paths = self.prepare()
        self.assertEqual(set(paths), {'key', 'agent_service', 'nexus_service', 'database', 'operator'})
        for path in paths.values():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_uid, os.getuid())
        self.assertEqual(paths['agent_service'].read_bytes(), paths['nexus_service'].read_bytes())
        self.assertNotEqual(paths['operator'].read_bytes(), paths['agent_service'].read_bytes())
        self.assertEqual(paths['database'].read_bytes(), b'synthetic-db-password')
        ring = dict(line.split('=', 1) for line in paths['key'].read_text().splitlines())
        self.assertEqual(len(base64.b64decode(ring['key.' + ring['active']], validate=True)), 32)

    def test_existing_operator_owner_is_reused_without_secret_duplication(self):
        operator_root = pathlib.Path(self.temporary.name) / 'remote'
        operator_root.mkdir(mode=0o711)
        owner = operator_root / 'operator.secret'
        value = b'o' * 43
        owner.write_bytes(value + b'\n')
        owner.chmod(0o600)
        paths = self.provision.prepare(self.root, os.getuid(), os.getgid(),
                                       b'synthetic-db-password', 'http://127.0.0.1:9099',
                                       operator_file=owner)
        self.assertEqual(paths['operator'], owner)
        self.assertFalse((self.root / 'operator.secret').exists())
        self.assertEqual(owner.read_bytes(), value + b'\n')

    def test_repeat_preserves_every_byte(self):
        paths = self.prepare()
        before = {name: path.read_bytes() for name, path in paths.items()}
        self.assertEqual({name: path.read_bytes() for name, path in self.prepare().items()}, before)

    def test_partial_setup_preserves_existing_key_and_agent_audience(self):
        paths = self.prepare()
        key, audience = paths['key'].read_bytes(), paths['agent_service'].read_bytes()
        paths['nexus_service'].unlink()
        paths['operator'].unlink()
        after = self.prepare()
        self.assertEqual(after['key'].read_bytes(), key)
        self.assertEqual(after['agent_service'].read_bytes(), audience)
        self.assertEqual(after['nexus_service'].read_bytes(), audience)

    def test_restrictive_umask_still_creates_explicit_permissions(self):
        previous = os.umask(0o777)
        try:
            paths = self.prepare()
            self.assertEqual(stat.S_IMODE(self.root.stat().st_mode), 0o700)
            self.assertTrue(all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in paths.values()))
        finally:
            os.umask(previous)

    def test_noncanonical_existing_bearer_is_rejected_without_replacement(self):
        paths = self.prepare()
        paths['agent_service'].write_bytes(b'a' * 43)
        paths['nexus_service'].write_bytes(b'a' * 43)
        with self.assertRaisesRegex(RuntimeError, 'Invalid existing credential'):
            self.prepare()
        self.assertEqual(paths['agent_service'].read_bytes(), b'a' * 43)

    def test_invalid_existing_key_is_rejected_without_rotation(self):
        paths = self.prepare()
        paths['key'].write_bytes(b'invalid-ring')
        with self.assertRaisesRegex(RuntimeError, 'Invalid existing key'):
            self.prepare()
        self.assertEqual(paths['key'].read_bytes(), b'invalid-ring')

    def test_unsafe_mode_symlink_and_hardlink_are_rejected(self):
        paths = self.prepare()
        service = paths['agent_service']
        contents = service.read_bytes()
        service.chmod(0o644)
        with self.assertRaises(RuntimeError):
            self.prepare()
        service.chmod(0o600)
        alias = self.root / 'alias'
        os.link(service, alias)
        with self.assertRaises(RuntimeError):
            self.prepare()
        alias.unlink()
        service.unlink()
        alias.write_bytes(contents)
        alias.chmod(0o600)
        service.symlink_to(alias)
        with self.assertRaises(RuntimeError):
            self.prepare()

    def test_conflicting_nexus_audience_and_operator_service_reuse_fail_closed(self):
        paths = self.prepare()
        paths['nexus_service'].write_bytes(base64.urlsafe_b64encode(b'z' * 32).rstrip(b'='))
        with self.assertRaisesRegex(RuntimeError, 'Conflicting service'):
            self.prepare()
        paths['nexus_service'].write_bytes(paths['agent_service'].read_bytes())
        paths['operator'].write_bytes(paths['agent_service'].read_bytes())
        with self.assertRaisesRegex(RuntimeError, 'Credentials must be distinct'):
            self.prepare()

    def test_writable_parent_is_rejected_before_creating_material(self):
        self.root.parent.chmod(0o777)
        with self.assertRaises(RuntimeError):
            self.prepare()
        self.assertFalse(self.root.exists())


class NormalEnvironmentRenderingTest(unittest.TestCase):
    def test_main_units_reference_protected_paths_and_environment_has_no_database_secret(self):
        import subprocess
        repository = pathlib.Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            environment = dict(os.environ, FORGE_AGENT_DB_PASSWORD='synthetic-runtime-db-canary')
            subprocess.run([str(repository / 'scripts/systemd/render-units.sh'), str(root),
                            str(root / 'forge-ai.env')], check=True, capture_output=True, env=environment)
            shared = (root / 'forge-ai.env').read_text()
            self.assertNotIn('synthetic-runtime-db-canary', shared)
            self.assertNotIn('FORGE_AGENT_DB_PASSWORD', shared)
            self.assertIn('EnvironmentFile=/etc/forge-ai/mcp/agent.env', (root / 'forge-agent.service').read_text())
            self.assertIn('EnvironmentFile=/etc/forge-ai/mcp/nexus.env', (root / 'forge-nexus.service').read_text())
            self.assertIn('NoNewPrivileges=false', (root / 'forge-agent.service').read_text())
            self.assertIn('SupplementaryGroups=forge-runtime', (root / 'forge-agent.service').read_text())
            self.assertIn('RemoteAccessAgentApplication', (root / 'forge-remote-agent.service').read_text())
            self.assertIn('RemoteAccessNexusApplication', (root / 'forge-remote-nexus.service').read_text())
