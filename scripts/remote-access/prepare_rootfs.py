#!/usr/bin/python3 -I
"""Prepare one immutable, credential-free command root from the reviewed image."""

import os
import pathlib
import posixpath
import stat
import subprocess
import sys
import tarfile
import tempfile


ROOT = pathlib.Path('/srv/forge-remote')
TARGET = ROOT / 'rootfs'
DOCKERFILE = pathlib.Path(__file__).resolve().parent / 'rootfs.Dockerfile'
IMAGE = 'forge-remote-rootfs-v1:local'
TOOLS = ('/bin/sh', '/usr/bin/git', '/usr/bin/python3', '/opt/java/openjdk/bin/java',
         '/usr/bin/mvn', '/usr/bin/node', '/usr/bin/npm')
MOUNTS = ('workspace', 'proc', 'dev', 'tmp', 'run')


def checked_directory(path):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise RuntimeError('REMOTE_ACCESS_ROOTFS_CONFLICT')


def inspect_rootfs(root):
    checked_directory(root)
    for relative in TOOLS:
        path = root / relative.lstrip('/')
        if not path.exists() or not path.is_file():
            raise RuntimeError('REMOTE_ACCESS_ROOTFS_NOT_READY')
    for relative in MOUNTS:
        path = root / relative
        if path.is_symlink() or not path.is_dir():
            raise RuntimeError('REMOTE_ACCESS_ROOTFS_NOT_READY')
    for relative in ('run/docker.sock', 'run/forge-remote', 'var/lib/forge-agent',
                     'etc/forge-remote'):
        if (root / relative).exists():
            raise RuntimeError('REMOTE_ACCESS_ROOTFS_CONFLICT')


def safe_member(member, destination):
    # Python's data filter rejects traversal, special devices and escaping links.
    # Image symlinks use container-absolute paths; make them relative to the
    # extraction root so they retain their meaning inside the later chroot.
    if member.issym() and member.linkname.startswith('/'):
        target = posixpath.normpath(member.linkname).lstrip('/')
        source_parent = posixpath.dirname(member.name)
        member = member.replace(linkname=posixpath.relpath(target, source_parent))
    member = tarfile.data_filter(member, destination)
    if member is None or not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
        raise RuntimeError('REMOTE_ACCESS_ROOTFS_IMAGE_UNSAFE')
    mode = None if member.mode is None else member.mode & ~0o6022
    return member.replace(uid=0, gid=0, uname='root', gname='root', mode=mode)


def extract_image(archive, destination):
    with tarfile.open(archive, 'r') as contents:
        contents.extractall(destination, filter=safe_member)
    for relative in MOUNTS:
        mount = destination / relative
        if mount.is_symlink():
            raise RuntimeError('REMOTE_ACCESS_ROOTFS_IMAGE_UNSAFE')
        mount.mkdir(exist_ok=True)
        mount.chmod(0o755)
    inspect_rootfs(destination)


def run(*args, timeout=300):
    subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True,
                   text=True, check=True, timeout=timeout)


def prepare():
    if os.geteuid() != 0:
        raise RuntimeError('REMOTE_ACCESS_REQUIRES_ROOT')
    if ROOT.exists() or ROOT.is_symlink():
        checked_directory(ROOT)
    else:
        ROOT.mkdir(mode=0o755)
    if TARGET.exists() or TARGET.is_symlink():
        inspect_rootfs(TARGET)
        return
    checked_directory(DOCKERFILE.parent)
    if DOCKERFILE.is_symlink() or DOCKERFILE.stat().st_uid != 0:
        raise RuntimeError('REMOTE_ACCESS_ROOTFS_IMAGE_UNSAFE')
    run('/usr/bin/docker', 'build', '--pull=false', '--network=default',
        '--file', str(DOCKERFILE), '--tag', IMAGE, str(DOCKERFILE.parent),
        timeout=900)
    with tempfile.TemporaryDirectory(prefix='.rootfs-build-', dir=ROOT) as temporary:
        temp = pathlib.Path(temporary)
        container = subprocess.run(['/usr/bin/docker', 'create', '--network=none', IMAGE],
                                   stdin=subprocess.DEVNULL, capture_output=True,
                                   text=True, check=True, timeout=30).stdout.strip()
        if not container or len(container) != 64 or any(char not in '0123456789abcdef' for char in container):
            raise RuntimeError('REMOTE_ACCESS_ROOTFS_IMAGE_UNSAFE')
        try:
            archive = temp / 'image.tar'
            run('/usr/bin/docker', 'export', '--output', str(archive), container, timeout=300)
            staging = temp / 'prepared'
            staging.mkdir(mode=0o755)
            extract_image(archive, staging)
            staging.rename(TARGET)
        finally:
            run('/usr/bin/docker', 'rm', '--force', container, timeout=30)


if __name__ == '__main__':
    try:
        prepare()
        print('REMOTE_ACCESS_ROOTFS_PREPARED')
    except (OSError, RuntimeError, tarfile.TarError, subprocess.SubprocessError):
        print('REMOTE_ACCESS_ROOTFS_NOT_READY', file=sys.stderr)
        sys.exit(1)
