"""Rootfs extraction is tested without Docker, root privileges, or host writes."""

import importlib.util
import io
import os
import pathlib
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'prepare_rootfs.py'
spec = importlib.util.spec_from_file_location('prepare_rootfs', SCRIPT)
rootfs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rootfs)


class PrepareRootfsTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('FORGE_ROOTFS_FULL_TEST') == '1' and os.geteuid() == 0,
                         'requires opt-in and root for privileged Docker export and extraction')
    def test_built_image_extracts_to_usable_isolated_rootfs(self):
        container = subprocess.run(['docker', 'create', '--network', 'none', rootfs.IMAGE],
                                   capture_output=True, text=True, check=True, timeout=30).stdout.strip()
        try:
            with tempfile.TemporaryDirectory(prefix='forge-rootfs-test-', dir='/srv') as directory:
                archive = pathlib.Path(directory) / 'image.tar'
                prepared = pathlib.Path(directory) / 'prepared'
                prepared.mkdir()
                subprocess.run(['docker', 'export', '--output', str(archive), container],
                               capture_output=True, check=True, timeout=300)
                rootfs.extract_image(archive, prepared)
                self.assertTrue((prepared / 'usr/bin/git').is_file())
                self.assertTrue((prepared / 'opt/java/openjdk/bin/java').is_file())
        finally:
            subprocess.run(['docker', 'rm', '--force', container], capture_output=True,
                           check=True, timeout=30)

    @unittest.skipUnless(os.environ.get('FORGE_ROOTFS_IMAGE_TEST') == '1', 'opt-in local Docker image inspection')
    def test_built_image_has_no_unsafe_export_members(self):
        container = subprocess.run(['docker', 'create', '--network', 'none', rootfs.IMAGE],
                                   capture_output=True, text=True, check=True, timeout=30).stdout.strip()
        try:
            export = subprocess.Popen(['docker', 'export', container], stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE)
            try:
                with tarfile.open(fileobj=export.stdout, mode='r|') as archive:
                    count = 0
                    for member in archive:
                        rootfs.safe_member(member, '/tmp/forge-remote-image-inspection')
                        count += 1
                self.assertGreater(count, 1000)
                self.assertEqual(export.wait(timeout=60), 0, export.stderr.read().decode())
            finally:
                if export.poll() is None:
                    export.kill()
                    export.wait(timeout=5)
                export.stdout.close()
                export.stderr.close()
        finally:
            subprocess.run(['docker', 'rm', '--force', container], capture_output=True,
                           check=True, timeout=30)

    def write_archive(self, path, names):
        with tarfile.open(path, 'w') as archive:
            for name, kind in names:
                member = tarfile.TarInfo(name)
                member.mode = 0o755
                if kind == 'file':
                    data = b'fixture-binary'
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
                elif kind == 'device':
                    member.type = tarfile.CHRTYPE
                    archive.addfile(member)
                else:
                    raise AssertionError(kind)

    def test_extract_rejects_path_traversal_without_host_write(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            archive = base / 'image.tar'
            self.write_archive(archive, [('../escaped', 'file')])
            destination = base / 'prepared'
            destination.mkdir()
            with self.assertRaises(tarfile.FilterError):
                rootfs.extract_image(archive, destination)
            self.assertFalse((base / 'escaped').exists())

    def test_extract_rejects_special_device(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            archive = base / 'image.tar'
            self.write_archive(archive, [('dev/unsafe', 'device')])
            destination = base / 'prepared'
            destination.mkdir()
            with self.assertRaises(tarfile.FilterError):
                rootfs.extract_image(archive, destination)

    def test_missing_toolchain_does_not_pass_readiness(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            archive = base / 'image.tar'
            self.write_archive(archive, [('bin/sh', 'file')])
            destination = base / 'prepared'
            destination.mkdir()
            with mock.patch.object(rootfs, 'checked_directory'):
                with self.assertRaisesRegex(RuntimeError, 'REMOTE_ACCESS_ROOTFS_NOT_READY'):
                    rootfs.extract_image(archive, destination)


if __name__ == '__main__':
    unittest.main()
