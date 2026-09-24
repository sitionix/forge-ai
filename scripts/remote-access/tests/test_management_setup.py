import importlib.util
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

BASE = pathlib.Path(__file__).resolve().parents[1]

class ManagementSetupTest(unittest.TestCase):
    def setup_module(self):
        spec = importlib.util.spec_from_file_location('prepare_management', BASE/'prepare_management.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        return module

    def test_generated_runtime_has_distinct_protected_credentials_and_explicit_loopback(self):
        setup = self.setup_module()
        with tempfile.TemporaryDirectory() as temp, patch.object(setup.os, 'chown'):
            root = pathlib.Path(temp)/'management'
            setup.prepare(root, os.getuid(), os.getgid(), os.getuid(), os.getgid(), 'http://127.0.0.1:9099')
            agent = root/'agent-service.secret'; nexus = root/'nexus-service.secret'; operator = root/'operator.secret'
            self.assertEqual(agent.read_bytes(), nexus.read_bytes())
            self.assertNotEqual(agent.read_bytes(), operator.read_bytes())
            for file in [agent, nexus, operator]: self.assertEqual(0o600, file.stat().st_mode & 0o777)
            self.assertIn('FORGE_AGENT_HOST=127.0.0.1', (root/'agent.env').read_text())
            self.assertIn('FORGE_NEXUS_HOST=127.0.0.1', (root/'nexus.env').read_text())
            self.assertIn('FORGE_REMOTE_ACCESS_AGENT_READ_TIMEOUT=120s', (root/'nexus.env').read_text())
            for env_file in [root/'agent.env', root/'nexus.env']:
                for secret in [agent, nexus, operator]:
                    self.assertNotIn(secret.read_text().strip(), env_file.read_text())
            before = agent.read_bytes()
            setup.prepare(root, os.getuid(), os.getgid(), os.getuid(), os.getgid(), 'http://127.0.0.1:9099')
            self.assertEqual(before, agent.read_bytes())

    def test_restrictive_umask_does_not_make_the_root_owned_parent_inaccessible(self):
        setup = self.setup_module()
        with tempfile.TemporaryDirectory() as temp, patch.object(setup.os, 'chown'):
            root = pathlib.Path(temp)/'management'
            previous = os.umask(0o077)
            try:
                setup.prepare(root, os.getuid(), os.getgid(), os.getuid(), os.getgid(), 'http://127.0.0.1:9099')
            finally:
                os.umask(previous)
            self.assertEqual(0o711, root.stat().st_mode & 0o777)

    def test_conflicting_or_symlink_artifacts_are_not_overwritten(self):
        setup = self.setup_module()
        with tempfile.TemporaryDirectory() as temp, patch.object(setup.os, 'chown'):
            root = pathlib.Path(temp)/'management';root.mkdir(mode=0o711)
            target = pathlib.Path(temp)/'foreign';target.write_text('preserve')
            (root/'agent-service.secret').symlink_to(target)
            with self.assertRaises(RuntimeError): setup.prepare(root, os.getuid(), os.getgid(), os.getuid(), os.getgid(), 'http://127.0.0.1:9099')
            self.assertEqual('preserve',target.read_text())

    def test_non_loopback_origin_is_rejected_before_writing(self):
        setup = self.setup_module()
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)/'management'
            with self.assertRaises(ValueError): setup.prepare(root,0,0,0,0,'http://192.168.1.2:9099')
            self.assertFalse(root.exists())
