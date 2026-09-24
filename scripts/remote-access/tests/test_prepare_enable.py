import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import prepare_enable


class PrepareEnableTest(unittest.TestCase):
    def fixture(self, root):
        for name in prepare_enable.PACKAGE_FILES:
            source = root / 'scripts/remote-access' / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(('reviewed-' + name).encode())
        jar = root / prepare_enable.AGENT_JAR
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(b'reviewed-agent-jar')

    def test_stage_copies_only_pinned_package_bytes_on_enable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / 'source'
            self.fixture(root)
            manifest = prepare_enable.build_manifest(root)
            package = pathlib.Path(directory) / 'package'
            jar = pathlib.Path(directory) / 'agent.jar'
            prepare_enable.stage(manifest, package, jar)
            self.assertEqual((root / prepare_enable.AGENT_JAR).read_bytes(), jar.read_bytes())
            for name in prepare_enable.PACKAGE_FILES:
                self.assertEqual((root / 'scripts/remote-access' / name).read_bytes(),
                                 (package / name).read_bytes())

    def test_modified_source_after_manifest_cannot_be_staged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / 'source'
            self.fixture(root)
            manifest = prepare_enable.build_manifest(root)
            (root / 'scripts/remote-access/install.py').write_bytes(b'modified')
            package = pathlib.Path(directory) / 'package'
            jar = pathlib.Path(directory) / 'agent.jar'
            with self.assertRaisesRegex(RuntimeError, 'REMOTE_ACCESS_PACKAGE_CHANGED'):
                prepare_enable.stage(manifest, package, jar)
            self.assertFalse(package.exists())
            self.assertFalse(jar.exists())

    def test_symlinked_source_is_rejected_even_with_matching_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / 'source'
            self.fixture(root)
            manifest = prepare_enable.build_manifest(root)
            source = root / 'scripts/remote-access/install.py'
            copied = pathlib.Path(directory) / 'copied-install.py'
            copied.write_bytes(source.read_bytes())
            source.unlink()
            source.symlink_to(copied)
            with self.assertRaisesRegex(RuntimeError, 'REMOTE_ACCESS_PACKAGE_CHANGED'):
                prepare_enable.stage(manifest, pathlib.Path(directory) / 'package',
                                     pathlib.Path(directory) / 'agent.jar')

    def test_runtime_starts_ssh_only_after_package_and_setup_succeed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / 'source'
            self.fixture(root)
            calls = []
            package = pathlib.Path(directory) / 'package'
            jar = pathlib.Path(directory) / 'agent.jar'
            def run(command):
                calls.append(tuple(command))
                if command[0] == '/usr/bin/systemctl':
                    self.assertTrue((package / 'prepare_startup.py').is_file())
                    self.assertTrue(jar.is_file())
            prepare_enable.enable_runtime(prepare_enable.build_manifest(root), package,
                                          jar, 'operator', run, lambda _: True)
            self.assertEqual('/usr/bin/python3', calls[0][0])
            self.assertIn('prepare_startup.py', calls[0][2])
            self.assertEqual('ssh.service', calls[1][-1])

    def test_preparation_failure_never_starts_ssh(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / 'source'
            self.fixture(root)
            calls = []
            def run(command):
                calls.append(tuple(command))
                raise RuntimeError('setup failed')
            with self.assertRaisesRegex(RuntimeError, 'setup failed'):
                prepare_enable.enable_runtime(prepare_enable.build_manifest(root),
                                              pathlib.Path(directory) / 'package',
                                              pathlib.Path(directory) / 'agent.jar',
                                              'operator', run, lambda _: True)
            self.assertEqual(1, len(calls))
            self.assertEqual('/usr/bin/python3', calls[0][0])

    def test_failed_health_stops_every_remote_service(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / 'source'
            self.fixture(root)
            calls = []
            with self.assertRaisesRegex(RuntimeError, 'REMOTE_ACCESS_START_FAILED'):
                prepare_enable.enable_runtime(prepare_enable.build_manifest(root),
                                              pathlib.Path(directory) / 'package',
                                              pathlib.Path(directory) / 'agent.jar',
                                              'operator', lambda command: calls.append(tuple(command)),
                                              lambda _: False)
            stops = [command[-1] for command in calls if command[:2] == ('/usr/bin/systemctl', 'stop')]
            self.assertEqual(['forge-remote-nexus.service', 'forge-remote-agent.service'], stops)
            self.assertNotIn('ssh.service', stops)


if __name__ == '__main__':
    unittest.main()
