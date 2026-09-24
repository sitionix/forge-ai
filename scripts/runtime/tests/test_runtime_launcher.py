"""Unit contract tests only; mocked systemd is never isolation evidence."""
import importlib.util
import pathlib
import tempfile
import unittest
import errno
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
        with patch.object(self.launcher,'locked_receipt',return_value=receipt), patch.object(self.launcher,'stop_unit') as stop:
            self.launcher.stop({},'owned')
            stop.assert_called_once_with({},'owned')

    def test_root_file_rejects_symlink_and_writable_ancestor(self):
        if not SOURCE.exists(): self.skipTest('implementation absent')
        with tempfile.TemporaryDirectory() as folder:
            path=pathlib.Path(folder)/'config'; path.write_text('{}')
            with self.assertRaises(ValueError): self.launcher.trusted_path(path)
            link=pathlib.Path(folder)/'link'; link.symlink_to(path)
            with self.assertRaises(ValueError): self.launcher.trusted_path(link)

if __name__ == '__main__': unittest.main()
