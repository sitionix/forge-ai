"""Disposable managed workspace migration; not privileged runtime acceptance."""
import importlib.util
import pathlib
import tempfile
import unittest


class WorkspaceAdoptionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = pathlib.Path(self.temp.name)
        self.source, self.destination = root / 'old', root / 'managed'
        self.source.mkdir()
        self.destination.mkdir()
        self.project = self.source / 'project-a'
        (self.project / 'repo/.git').mkdir(parents=True)
        (self.project / 'repo/modified').write_text('local edits')
        (self.project / 'repo/untracked').write_text('untracked')
        spec = importlib.util.spec_from_file_location('prepare_workspaces', pathlib.Path(__file__).parents[1] / 'prepare_workspaces.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_preserves_local_changes_source_and_repeat(self):
        self.module.adopt(self.source, self.destination)
        target = self.destination / 'project-a/repo'
        self.assertEqual((target / 'modified').read_text(), 'local edits')
        self.assertEqual((target / 'untracked').read_text(), 'untracked')
        self.assertEqual((self.project / 'repo/modified').read_text(), 'local edits')
        (target / 'modified').write_text('new edits')
        self.module.adopt(self.source, self.destination)
        self.assertEqual((target / 'modified').read_text(), 'new edits')
        self.assertEqual((self.destination / 'project-a').stat().st_mode & 0o7777, 0o2750)
        self.assertEqual(target.stat().st_mode & 0o7777, 0o2770)

    def test_relative_internal_symlink_is_preserved_without_following_it(self):
        link = self.project / 'repo/alias'
        link.symlink_to('modified')
        self.module.adopt(self.source, self.destination)
        copied = self.destination / 'project-a/repo/alias'
        self.assertTrue(copied.is_symlink())
        self.assertEqual(copied.readlink(), pathlib.Path('modified'))
        self.assertEqual(copied.read_text(), 'local edits')
        self.assertTrue(link.is_symlink())

    def test_conflicting_destination_is_not_overwritten(self):
        (self.destination / 'project-a').mkdir()
        with self.assertRaises(RuntimeError):
            self.module.adopt(self.source, self.destination)
        self.assertFalse((self.destination / 'project-a/repo').exists())

    def test_symlinks_are_not_followed_and_partial_copy_is_not_published(self):
        (self.project / 'repo/escape').symlink_to('/etc')
        with self.assertRaises(RuntimeError):
            self.module.adopt(self.source, self.destination)
        self.assertFalse((self.destination / 'project-a').exists())
        self.assertEqual(list(self.destination.iterdir()), [])
        self.assertTrue((self.project / 'repo/escape').is_symlink())

    def test_directory_replacement_cannot_copy_external_content(self):
        from unittest.mock import patch
        repository = self.project
        external = self.source.parent / 'external'
        external.mkdir()
        (external / 'credential-canary').write_text('synthetic-secret')
        check = self.module.directory
        checks = 0
        def replace_after_check(path):
            nonlocal checks
            check(path)
            if path == repository:
                checks += 1
            if path == repository and checks == 2:
                repository.rename(self.source / 'original')
                repository.symlink_to(external, target_is_directory=True)
        with patch.object(self.module, 'directory', side_effect=replace_after_check):
            with self.assertRaises((RuntimeError, OSError)):
                self.module.adopt(self.source, self.destination)
        self.assertFalse((self.destination / 'project-a').exists())

    def test_symlink_root_rejected(self):
        alias = self.source.parent / 'alias'
        alias.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(RuntimeError):
            self.module.adopt(alias, self.destination)
