"""Cleanup regression tests: mocked systemd, real temporary artifacts, no root."""
import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import privileged_boundary as probe


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.base = self.home / 'rootfs'
        self.base.mkdir()
        (self.base / 'canary').write_text('keep me')
        self.private = self.home / 'private'
        self.links = self.home / 'links'
        self.private.mkdir()
        self.links.mkdir()
        self.prefix = 'forge-stage0-test'
        for role in ['a', 'b']:
            directory = self.private / (self.prefix + '-' + role)
            directory.mkdir()
            (directory / 'canary').write_text('keep state')
            (self.links / directory.name).symlink_to(directory, target_is_directory=True)
        self.empty_proc = self.home / 'proc'
        self.empty_proc.mkdir()
        self.output = io.StringIO()
        self.commands = []

    def path(self, *parts):
        if parts == ('/var/lib/private',):
            return self.private
        if parts == ('/var/lib',):
            return self.links
        if parts == ('/proc',):
            return self.empty_proc
        return Path(*parts)

    def test_unavailable_systemd_in_real_main_preserves_artifacts(self):
        def unavailable(*args, **kwargs):
            self.commands.append(args)
            return subprocess.CompletedProcess(args, 1, '', 'Failed to connect to bus')

        with patch.object(probe.os, 'geteuid', return_value=0), \
             patch.object(probe.tempfile, 'mkdtemp', return_value=str(self.base)), \
             patch.object(probe.pwd, 'getpwuid', side_effect=KeyError), \
             patch.object(probe, 'Path', side_effect=self.path), \
             patch.object(probe, 'checked', side_effect=RuntimeError('original probe failure')), \
             patch.object(probe, 'run', side_effect=unavailable), \
             patch.object(probe.shutil, 'rmtree', wraps=probe.shutil.rmtree) as remove, \
             patch.object(Path, 'unlink', autospec=True) as unlink, \
             contextlib.redirect_stdout(self.output):
            with self.assertRaises(RuntimeError) as raised:
                probe.main('unused.tar')
        self.assertNotIn('CLEANUP_PASS', self.output.getvalue())
        self.assertIn('CLEANUP_FAILED', str(raised.exception))
        self.assertIn(str(self.base), str(raised.exception))
        self.assertTrue((self.base / 'canary').exists())
        self.assertTrue(all((self.private / (self.prefix + '-' + role) / 'canary').exists() for role in ['a', 'b']))
        remove.assert_not_called()
        unlink.assert_not_called()
        self.assertEqual(3, sum(cmd[:2] == ('systemctl', 'stop') for cmd in self.commands))

    def invoke_cleanup(self, stop_result=None, show_result=None, faults=None):
        units = ['probe-a.service', 'probe-b.service', 'probe-c.service']
        good = 'LoadState=loaded\nActiveState=inactive\nSubState=dead\nMainPID=0\n'
        def command(*args, **kwargs):
            self.commands.append(args)
            operation, unit = args[1:3]
            value = (faults or {}).get((operation, unit))
            if isinstance(value, Exception):
                raise value
            if value is not None:
                return value
            if operation == 'stop':
                return stop_result or subprocess.CompletedProcess(args, 0, '', '')
            if operation == 'show':
                return show_result or subprocess.CompletedProcess(args, 0, good, '')
            self.fail('Unexpected cleanup command: ' + repr(args))
        with patch.object(probe, 'Path', side_effect=self.path), \
             patch.object(probe, 'run', side_effect=command), \
             patch.object(probe.shutil, 'rmtree', wraps=probe.shutil.rmtree) as remove, \
             patch.object(Path, 'unlink', autospec=True, side_effect=Path.unlink) as unlink, \
             contextlib.redirect_stdout(self.output):
            error = None
            try:
                probe.cleanup(units, self.base, self.prefix)
            except RuntimeError as exc:
                error = exc
        return error, remove, unlink

    def assert_retained(self, result):
        error, remove, unlink = result
        self.assertIsInstance(error, RuntimeError)
        self.assertIn('CLEANUP_FAILED', str(error))
        self.assertIn(str(self.base), str(error))
        self.assertNotIn('CLEANUP_PASS', self.output.getvalue())
        self.assertTrue((self.base / 'canary').exists())
        for role in ['a', 'b']:
            self.assertTrue((self.private / (self.prefix + '-' + role) / 'canary').exists())
            self.assertTrue((self.links / (self.prefix + '-' + role)).is_symlink())
        remove.assert_not_called()
        unlink.assert_not_called()
        self.assertEqual(3, sum(cmd[:2] == ('systemctl', 'stop') for cmd in self.commands))
        self.assertEqual(3, sum(cmd[:2] == ('systemctl', 'show') for cmd in self.commands))

    def test_nonzero_inspection_with_inactive_text_is_rejected(self):
        result = subprocess.CompletedProcess([], 1,
            'LoadState=loaded\nActiveState=inactive\nSubState=dead\nMainPID=0\n', 'bus failure')
        self.assert_retained(self.invoke_cleanup(show_result=result))

    def test_nonzero_stop_for_present_unit_is_rejected(self):
        self.assert_retained(self.invoke_cleanup(stop_result=subprocess.CompletedProcess([], 1, '', '')))

    def test_empty_and_malformed_successful_inspection_is_rejected(self):
        for text in ['', 'inactive', 'LoadState=loaded\nActiveState=inactive\n',
                     'LoadState=not-found\nActiveState=inactive\nSubState=dead\nMainPID=0\nExtra=1',
                     'LoadState=loaded\nActiveState=inactive\nSubState=dead\nMainPID=0\nMainPID=0']:
            with self.subTest(text=text):
                self.commands.clear()
                self.assert_retained(self.invoke_cleanup(show_result=subprocess.CompletedProcess([], 0, text, '')))

    def test_active_deactivating_failed_and_live_pid_are_rejected(self):
        for active, sub, pid in [('active','running','15'), ('deactivating','stop-sigterm','15'),
                                 ('failed','failed','0'), ('inactive','dead','15')]:
            with self.subTest(active=active, pid=pid):
                self.commands.clear()
                text = f'LoadState=loaded\nActiveState={active}\nSubState={sub}\nMainPID={pid}\n'
                self.assert_retained(self.invoke_cleanup(show_result=subprocess.CompletedProcess([], 0, text, '')))

    def test_timeout_and_exception_do_not_skip_other_units(self):
        for operation in ['stop', 'show']:
            for failure in [subprocess.TimeoutExpired('systemctl', 30), OSError('bus unavailable')]:
                with self.subTest(operation=operation, failure=type(failure).__name__):
                    self.commands.clear()
                    self.assert_retained(self.invoke_cleanup(faults={(operation,'probe-a.service'): failure}))

    def test_all_stopped_removes_only_probe_artifacts(self):
        unrelated = self.private / 'unrelated'
        unrelated.mkdir()
        (unrelated / 'canary').write_text('untouched')
        error, remove, unlink = self.invoke_cleanup()
        self.assertIsNone(error)
        self.assertIn('CLEANUP_PASS', self.output.getvalue())
        self.assertFalse(self.base.exists())
        self.assertTrue((unrelated / 'canary').exists())
        self.assertEqual(3, remove.call_count)
        self.assertEqual(2, unlink.call_count)
        for role in ['a','b']:
            self.assertFalse((self.private / (self.prefix + '-' + role)).exists())
            self.assertFalse((self.links / (self.prefix + '-' + role)).is_symlink())

    def test_explicit_absence_allows_failed_stop(self):
        absent = subprocess.CompletedProcess([], 0,
            'LoadState=not-found\nActiveState=inactive\nSubState=dead\nMainPID=0\n', '')
        error, _, _ = self.invoke_cleanup(faults={
            ('stop','probe-c.service'): subprocess.CompletedProcess([], 5, '', 'ignored'),
            ('show','probe-c.service'): absent})
        self.assertIsNone(error)
        self.assertIn('CLEANUP_PASS', self.output.getvalue())
        self.assertFalse(self.base.exists())

    def test_early_main_failure_remains_visible_after_successful_cleanup(self):
        count = 0
        def systemctl(*args, **kwargs):
            nonlocal count
            if args[1] == 'stop':
                count += 1
                return subprocess.CompletedProcess(args, 0 if count == 1 else 5, '', '')
            load = 'loaded' if count == 1 else 'not-found'
            return subprocess.CompletedProcess(args, 0,
                f'LoadState={load}\nActiveState=inactive\nSubState=dead\nMainPID=0\n', '')
        original = RuntimeError('original probe failure')
        with patch.object(probe.os, 'geteuid', return_value=0), \
             patch.object(probe.tempfile, 'mkdtemp', return_value=str(self.base)), \
             patch.object(probe.pwd, 'getpwuid', side_effect=KeyError), \
             patch.object(probe, 'Path', side_effect=self.path), \
             patch.object(probe, 'checked', side_effect=original), \
             patch.object(probe, 'run', side_effect=systemctl), \
             contextlib.redirect_stdout(self.output):
            with self.assertRaises(RuntimeError) as raised:
                probe.main('unused.tar')
        self.assertIs(raised.exception, original)
        self.assertIn('CLEANUP_PASS', self.output.getvalue())
        self.assertFalse(self.base.exists())
        self.assertEqual(3, count)


if __name__ == '__main__':
    unittest.main()
