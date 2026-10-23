"""Exercise the bundled reader using the interpreter running this test suite."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HELPER = (Path(__file__).resolve().parents[3] / 'services/forge-agent/infrastructure/codex/'
          'src/main/resources/codex/project_agent_inventory.py').read_text()
DEFINITION = '''name = "R\\u00e9viewer"
description = 'Reviews # changes'
developer_instructions = """Keep complete instructions.
Inspect \\"quoted\\" text and \\u03bb carefully."""
model = 'native-model'
model_reasoning_effort = 'high'
'''


class NativeAgentReaderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.agent = self.root / '.codex/agents/review.toml'
        self.agent.parent.mkdir(parents=True)
        self.agent.write_text(DEFINITION)
        self.reference = hashlib.sha256(('codex\0agent-file\0' + str(self.agent)).encode()).hexdigest()

    def read(self, operation=None, prefix='', **request):
        payload = {'projectRoot': str(self.root), 'roots': [str(self.root)], **request}
        if operation == 'resolve':
            payload['resolveId'] = self.reference
        if operation == 'manifest':
            payload['resourceManifest'] = True
        result = subprocess.run([sys.executable, '-I', '-c', prefix + HELPER],
                                input=json.dumps(payload), text=True, capture_output=True,
                                timeout=5, check=True)
        self.assertEqual('', result.stderr)
        return json.loads(result.stdout)

    def test_installed_parser_preserves_toml_values_for_all_operations(self):
        inventory = self.read()
        self.assertEqual([], inventory['diagnostics'])
        self.assertEqual('Réviewer', inventory['items'][0]['name'])
        definition = self.read('resolve')['resolved'][0]
        self.assertEqual('Keep complete instructions.\nInspect "quoted" text and λ carefully.', definition['instructions'])
        self.assertEqual('native-model', definition['model'])
        self.assertEqual('high', definition['effort'])
        fingerprint = hashlib.sha256(DEFINITION.encode()).hexdigest()
        self.assertEqual(fingerprint, definition['fingerprint'])
        self.assertEqual(fingerprint, self.read('manifest')['manifest'][str(self.agent)])

    def test_unverified_versions_fail_closed_consistently(self):
        for version in [(3, 10, 16), (3, 13, 9), (3, 15, 0), (4, 0, 0)]:
            for operation in [None, 'resolve', 'manifest']:
                with self.subTest(version=version, operation=operation):
                    prefix = ('import sys\nfrom collections import namedtuple\n'
                              'sys.version_info = namedtuple("Version", "major minor micro releaselevel serial")'
                              f'(*{version!r}, "final", 0)\n')
                    result = self.read(operation, prefix)
                    self.assertEqual(['RUNTIME_UNAVAILABLE'], result['diagnostics'])
                    self.assertFalse(result['runtimeCompatible'])
                    self.assertEqual([], result['items'])

    def test_incompatible_parser_and_prerelease_are_rejected(self):
        for prefix in ['import tomllib\ntomllib.loads = lambda text: {}\n',
                       'import sys\nfrom collections import namedtuple\n'
                       'sys.version_info = namedtuple("Version", "major minor micro releaselevel serial")'
                       '(3, 12, 0, "candidate", 1)\n',
                       'import sys\nsys.implementation.name = "pypy"\n']:
            result = self.read('manifest', prefix)
            self.assertEqual(['RUNTIME_UNAVAILABLE'], result['diagnostics'])
            self.assertEqual({}, result['manifest'])

    def test_malformed_and_unsupported_toml_never_resolve(self):
        for suffix in ["model = 'duplicate'\n", "sandbox_mode = 'danger-full-access'\n",
                       'unknown = [\n', 'unknown = "\\e"\n', '[permissions]\nnetwork = true\n']:
            with self.subTest(suffix=suffix):
                self.agent.write_text(DEFINITION + suffix)
                result = self.read('resolve')
                self.assertEqual([], result['resolved'])
                self.assertIn('DISCOVERY_FAILED', result['diagnostics'])

    def test_resource_change_and_symlink_replacement_are_detected(self):
        first = self.read('manifest')['manifest']
        self.agent.write_text(DEFINITION + '# new revision\n')
        self.assertNotEqual(first, self.read('manifest')['manifest'])
        outside = self.root / 'outside.toml'
        outside.write_text(DEFINITION)
        self.agent.unlink()
        self.agent.symlink_to(outside)
        self.assertIn('UNSAFE_RESOURCE', self.read('resolve')['diagnostics'])
        self.assertTrue(self.read('manifest')['diagnostics'])

    def test_directory_symlinks_and_parent_traversal_are_rejected(self):
        nested = self.root / 'nested'
        nested.mkdir()
        (nested / '.codex').symlink_to(self.root / '.codex', target_is_directory=True)
        self.assertIn('UNSAFE_RESOURCE', self.read(roots=[str(nested)])['diagnostics'])
        result = self.read('manifest', roots=[str(self.root) + '/../outside'])
        self.assertTrue(result['diagnostics'])
        self.assertEqual({}, result['manifest'])


if __name__ == '__main__':
    print(sys.version, flush=True)
    unittest.main()
