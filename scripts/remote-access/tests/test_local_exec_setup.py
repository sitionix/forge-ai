import importlib.util
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

BASE = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare_local_exec', BASE/'prepare_local_exec.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class LocalExecSetupTest(unittest.TestCase):
    def test_config_has_explicit_local_operator_and_protected_runtime(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(setup.os, 'chown'):
            root = pathlib.Path(temp)
            runtime = root/'run'/'local-exec'; runtime.parent.mkdir(); runtime.parent.chmod(0o755)
            management = root/'management'; management.mkdir(mode=0o711)
            tmpfiles = root/'local-exec.conf'
            setup.prepare(runtime, management, tmpfiles, 'local-codex', os.getuid(), os.getgid(), os.getuid(), os.getgid())
            self.assertEqual(0o2750, runtime.stat().st_mode & 0o7777)
            self.assertEqual(0o600, (management/'local-exec-agent.env').stat().st_mode & 0o777)
            self.assertIn('FORGE_AGENT_REMOTE_ACCESS_LOCAL_EXEC_ENABLED=true', (management/'local-exec-agent.env').read_text())
            self.assertIn('FORGE_AGENT_REMOTE_ACCESS_LOCAL_EXEC_OPERATOR_USER=local-codex',
                          (management/'local-exec-agent.env').read_text())
            self.assertIn('2750 forge-control local-codex', tmpfiles.read_text())
            setup.prepare(runtime, management, tmpfiles, 'local-codex', os.getuid(), os.getgid(), os.getuid(), os.getgid())

    def test_conflicting_files_and_unsafe_operator_rejected(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(setup.os, 'chown'):
            root = pathlib.Path(temp)
            runtime = root/'run'/'local-exec'; runtime.parent.mkdir(); runtime.parent.chmod(0o755)
            management = root/'management'; management.mkdir(mode=0o711)
            tmpfiles = root/'local-exec.conf'
            with self.assertRaises(ValueError):
                setup.prepare(runtime, management, tmpfiles, 'forge-ssh', os.getuid(), os.getgid(), os.getuid(), os.getgid())
            setup.prepare(runtime, management, tmpfiles, 'local-codex', os.getuid(), os.getgid(), os.getuid(), os.getgid())
            (management/'local-exec-agent.env').write_text('conflict')
            with self.assertRaises(RuntimeError):
                setup.prepare(runtime, management, tmpfiles, 'local-codex', os.getuid(), os.getgid(), os.getuid(), os.getgid())


if __name__ == '__main__':
    unittest.main()
