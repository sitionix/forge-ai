"""Unprivileged synthetic fixture file-handling regressions; no root/systemd."""
import importlib.util
import hashlib
import os
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('fixture',Path(__file__).parents[1]/'privileged_boundary.py')
fixture=importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)

class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name); self.source=self.root/'source'; self.destination=self.root/'bwrap'
        self.payload=b'\x7fELFsynthetic-only'; self.source.write_bytes(self.payload)
        self.digest=hashlib.sha256(self.payload).hexdigest()

    def copy(self):
        fixture.copy_pinned_elf(self.source,self.destination,self.digest)

    def test_pinned_elf_bytes_owner_and_mode(self):
        self.copy(); self.assertEqual(self.destination.read_bytes(),self.payload)
        self.assertEqual(self.destination.stat().st_mode & 0o7777,0o755)
        self.assertEqual(self.destination.stat().st_uid,os.geteuid())
    def test_pinned_elf_bad_hash_has_no_output(self):
        self.digest='0'*64
        with self.assertRaises(ValueError): self.copy()
        self.assertFalse(self.destination.exists())
    def test_pinned_elf_non_elf_has_no_output(self):
        self.source.write_bytes(b'not ELF'); self.digest=hashlib.sha256(b'not ELF').hexdigest()
        with self.assertRaises(ValueError): self.copy()
        self.assertFalse(self.destination.exists())
    def test_pinned_elf_source_symlink_rejected(self):
        target=self.root/'target'; self.source.rename(target); self.source.symlink_to(target)
        with self.assertRaises(OSError): self.copy()
        self.assertFalse(self.destination.exists())
    def test_pinned_elf_fifo_rejected_without_block(self):
        self.source.unlink(); os.mkfifo(self.source)
        with self.assertRaises(ValueError): self.copy()
        self.assertFalse(self.destination.exists())
    def test_pinned_elf_destination_symlink_preserves_target(self):
        target=self.root/'target'; target.write_bytes(b'survives'); target.chmod(0o600)
        self.destination.symlink_to(target)
        with self.assertRaises(FileExistsError): self.copy()
        self.assertEqual(target.read_bytes(),b'survives')
        self.assertEqual(target.stat().st_mode & 0o777,0o600)
    def test_pinned_elf_destination_parent_symlink_rejected(self):
        real=self.root/'real'; real.mkdir(); alias=self.root/'alias'; alias.symlink_to(real)
        self.destination=alias/'bwrap'
        with self.assertRaises(OSError): self.copy()
        self.assertEqual(list(real.iterdir()),[])
    def test_pinned_elf_existing_output_not_overwritten(self):
        self.destination.write_bytes(b'survives')
        with self.assertRaises(FileExistsError): self.copy()
        self.assertEqual(self.destination.read_bytes(),b'survives')

    def test_busy_runtime_uid_is_rejected_before_setup(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'12345').mkdir()
            with self.assertRaisesRegex(RuntimeError,'already in use'):
                fixture.require_unused_runtime(os.getuid(),root)
    def test_other_process_owner_does_not_block_runtime_uid(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'12345').mkdir()
            fixture.require_unused_runtime(os.getuid()+1,root)

    def test_writer_rejects_symlink_without_touching_target(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); target=root/'control'; target.write_text('synthetic')
            link=root/'script'; link.symlink_to(target)
            with self.assertRaises(OSError): fixture.write(link,'changed',0o755,os.getuid(),os.getgid())
            self.assertEqual('synthetic',target.read_text())
    def test_reader_rejects_symlink_fifo_and_hardlink(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); target=root/'control'; target.write_text('synthetic')
            link=root/'link'; link.symlink_to(target)
            fifo=root/'fifo'; os.mkfifo(fifo)
            hard=root/'hard'; os.link(target,hard)
            for path in [link,fifo,hard]:
                with self.subTest(path=path.name),self.assertRaises((OSError,ValueError)):
                    fixture.read_output(path)
    def test_reader_is_bounded(self):
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'output'; target.write_text('x'*8193)
            with self.assertRaises(ValueError): fixture.read_output(target)
    def test_parent_symlink_cannot_redirect_root_write(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); target=root/'control'; target.mkdir(); (root/'alias').symlink_to(target)
            with self.assertRaises(OSError): fixture.write(root/'alias'/'new','changed',0o644,os.getuid(),os.getgid())
            self.assertFalse((target/'new').exists())

if __name__=='__main__': unittest.main()
