"""Installer rejection contracts with disposable files, not privileged install proof."""
import importlib.util
import os
import pathlib
import stat
import tempfile
import unittest
from unittest.mock import patch


class McpInstallationTest(unittest.TestCase):
    def setUp(self):
        source = pathlib.Path(__file__).parents[1] / 'install_mcp.py'
        spec = importlib.util.spec_from_file_location('install_mcp', source)
        self.install = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.install)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)

    def test_unprivileged_install_does_not_modify_machine(self):
        with patch.object(self.install.os, 'geteuid', return_value=1000):
            with self.assertRaisesRegex(RuntimeError, 'System privileges|System runtime'):
                self.install.install(self.root, 'fixture', self.root, self.root / 'db',
                                     self.root / 'workspace', self.root / 'material', self.root / 'env')
        self.assertEqual(list(self.root.iterdir()), [])

    def test_unsafe_ancestor_rejected_before_directory_creation(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(RuntimeError):
            self.install.protected_directory(alias / 'new', os.getuid(), os.getgid(), 0o700)
        self.assertFalse((self.root / 'new').exists())

    def test_runtime_owned_home_is_a_valid_private_parent(self):
        import types
        home = pathlib.Path('/srv/forge-runtime/home')
        def info(path):
            return types.SimpleNamespace(st_mode=0o40700 if path == home else 0o40755,
                                         st_uid=62002 if path == home else 0)
        with patch.object(pathlib.Path, 'lstat', info):
            self.install.trusted_parent(home / '.codex', trusted_uid=62002)
            with self.assertRaises(RuntimeError):
                self.install.trusted_parent(home / '.codex')

    def test_existing_configuration_conflict_does_not_rotate_it(self):
        value = self.root / 'config'
        value.write_bytes(b'original')
        value.chmod(0o600)
        with self.assertRaises(RuntimeError):
            self.install.write_once(value, b'replacement', os.getuid(), os.getgid())
        self.assertEqual(value.read_bytes(), b'original')

    def test_artifact_symlink_and_hardlink_are_not_overwritten(self):
        source, target = self.root / 'source', self.root / 'target'
        source.write_bytes(b'software')
        target.symlink_to(source)
        with patch.object(self.install, 'trusted_parent'):
            with self.assertRaises(RuntimeError):
                self.install.install_file(source, target, 0o755)
            target.unlink()
            os.link(source, target)
            with self.assertRaises(RuntimeError):
                self.install.install_file(source, target, 0o755)
        self.assertEqual(source.read_bytes(), b'software')

    def test_neutral_codex_workspace_is_managed_and_repeatable(self):
        workspace = self.root / 'workspaces'
        workspace.mkdir()
        with patch.object(self.install, 'trusted_parent'):
            neutral = self.install.provision_codex_runtime_workspace(workspace, os.getuid(), os.getgid())
            self.assertEqual(neutral, workspace / '.forge-codex-runtime')
            self.assertEqual(neutral.stat().st_uid, os.getuid())
            self.assertEqual(neutral.stat().st_gid, os.getgid())
            self.assertEqual(stat.S_IMODE(neutral.stat().st_mode), 0o2750)
            self.assertEqual(self.install.provision_codex_runtime_workspace(workspace, os.getuid(), os.getgid()), neutral)
            neutral.chmod(0o777)
            with self.assertRaises(RuntimeError):
                self.install.provision_codex_runtime_workspace(workspace, os.getuid(), os.getgid())

    def test_agent_environment_routes_codex_inside_launcher_workspace_root(self):
        workspace = pathlib.Path('/srv/forge/workspaces/forge-projects')
        content = self.install.agent_environment({'key': pathlib.Path('/synthetic/key'),
                                                  'database': pathlib.Path('/synthetic/database')},
                                                 workspace, workspace / '.forge-codex-runtime').decode()
        values = dict(line.split('=', 1) for line in content.splitlines())
        self.assertEqual(values['FORGE_AGENT_WORKSPACE_ROOT'], '"/srv/forge/workspaces/forge-projects"')
        self.assertEqual(values['FORGE_AGENT_CODEX_RUNTIME_CWD'],
                         '"/srv/forge/workspaces/forge-projects/.forge-codex-runtime"')
        self.assertNotIn('/tmp/forge-agent-codex-runtime', content)
