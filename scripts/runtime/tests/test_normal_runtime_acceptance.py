"""Acceptance cannot substitute old health, alternate endpoints or activation overrides."""
import importlib.util
import pathlib
import unittest
from unittest.mock import MagicMock


class NormalRuntimeAcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = pathlib.Path(__file__).parents[1] / 'mcp-normal-runtime-acceptance.py'
        spec = importlib.util.spec_from_file_location('normal_runtime_acceptance', source)
        cls.acceptance = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.acceptance)

    def test_requires_fresh_start_for_both_main_units(self):
        self.acceptance.validate_restart(100, [101, 102])
        for marker, starts in [(None, [101, 102]), (100, [99, 102]), (100, [101, 0])]:
            with self.subTest(marker=marker, starts=starts), self.assertRaises(ValueError):
                self.acceptance.validate_restart(marker, starts)

    def test_no_alternate_stub_endpoint(self):
        self.assertEqual(self.acceptance.BASE, 'http://127.0.0.1:9099/fgaisox')

    def test_effective_runtime_must_use_generated_paths_without_jvm_override(self):
        expected = {'FORGE_MCP_KEY_FILE': '/protected/key'}
        self.acceptance.validate_effective_configuration(expected, expected, ['java', '-jar', 'agent.jar'])
        for values, arguments in [({'FORGE_MCP_KEY_FILE': '/other/key'}, ['java']),
                                  (expected, ['java', '-Dforge.mcp.key-file=/other/key']),
                                  (dict(expected, JAVA_TOOL_OPTIONS='-Dforge.mcp.key-file=/other/key'), ['java'])]:
            with self.assertRaises(ValueError):
                self.acceptance.validate_effective_configuration(expected, values, arguments)

    def test_removed_activation_overrides_cannot_qualify_as_normal_runtime(self):
        self.acceptance.validate_environment({'FORGE_MCP_KEY_FILE': '/protected/key'})
        for values in [{'FORGE_MCP_' + 'ENABLED': 'true'},
                       {'JAVA_TOOL_OPTIONS': '-Dforge.mcp.' + 'enabled=true'}]:
            with self.assertRaises(ValueError):
                self.acceptance.validate_environment(values)

    def test_management_read_sends_no_operator_or_service_credentials(self):
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value
        response.status = 200
        response.read.return_value = b'[]'
        self.assertEqual(self.acceptance.request(opener, self.acceptance.CONNECTIONS), (200, b'[]'))
        request = opener.open.call_args.args[0]
        self.assertNotIn('Authorization', request.headers)
        self.assertNotIn('Cookie', request.headers)
        self.assertNotIn('X-forge-csrf', request.headers)
        self.assertNotIn('X-csrf-token', request.headers)
