#!/usr/bin/python3 -I
"""Adopt the old managed subtree as the control user, retaining the original."""
import os
import pathlib
import shutil
import stat
import tempfile

ADOPTION_MARKER = '.forge-adopted-from'
CLONE_ATTEMPTS = '.forge-clone-attempts'


def directory(path):
    if not stat.S_ISDIR(path.lstat().st_mode):
        raise RuntimeError('Managed workspace must be a directory without symlinks')


def copy_tree(source, target, gid):
    directory(source)
    fd = os.open(source, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        copy_directory(fd, target, gid, source, source)
    finally:
        os.close(fd)


def copy_directory(source_fd, target, gid, source_path, project_root):
    mode = 0o2750 if source_path == project_root / CLONE_ATTEMPTS else 0o2770
    target.mkdir(mode=mode)
    target.chmod(mode)
    os.chown(target, -1, gid)
    with os.scandir(source_fd) as children:
        for child in children:
            if source_path == project_root and child.name == ADOPTION_MARKER:
                raise RuntimeError('Reserved adoption metadata conflicts with managed source')
            info = child.stat(follow_symlinks=False)
            destination = target / child.name
            if stat.S_ISLNK(info.st_mode):
                link = pathlib.Path(os.readlink(child.name, dir_fd=source_fd))
                resolved = pathlib.Path(os.path.normpath(source_path / link))
                if link.is_absolute() or not resolved.is_relative_to(project_root):
                    raise RuntimeError('Managed symlink resolves outside its project')
                destination.symlink_to(link)
                continue
            if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode) and info.st_nlink == 1):
                raise RuntimeError('Managed source contains an unsupported link or special file')
            flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            if stat.S_ISDIR(info.st_mode):
                flags |= os.O_DIRECTORY
            fd = os.open(child.name, flags, dir_fd=source_fd)
            try:
                current = os.fstat(fd)
                if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
                    raise RuntimeError('Managed source changed during adoption')
                if stat.S_ISDIR(current.st_mode):
                    copy_directory(fd, destination, gid, source_path / child.name, project_root)
                else:
                    with os.fdopen(os.dup(fd), 'rb') as incoming, destination.open('xb') as outgoing:
                        shutil.copyfileobj(incoming, outgoing)
                        os.fchmod(outgoing.fileno(), 0o770 if info.st_mode & 0o111 else 0o660)
                        os.fchown(outgoing.fileno(), -1, gid)
            finally:
                os.close(fd)


def protect_clone_parent(project, gid):
    info = project.lstat()
    if info.st_uid != os.getuid() or info.st_gid != gid or stat.S_IMODE(info.st_mode) != 0o2750:
        raise RuntimeError('Unsafe adopted project parent')
    try:
        fd = os.open(project / CLONE_ATTEMPTS, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return
    try:
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or info.st_gid != gid or stat.S_IMODE(info.st_mode) not in (0o2750, 0o2770):
            raise RuntimeError('Unsafe adopted clone parent')
        # Reconcile only the reserved control-owned parent created by the old
        # adopter. Checkout/staging contents and local modifications stay intact.
        os.fchmod(fd, 0o2750)
    finally:
        os.close(fd)


def adopt(source, destination):
    source, destination = pathlib.Path(source), pathlib.Path(destination)
    directory(destination)
    if source.is_symlink():
        raise RuntimeError('Managed source must not be a symlink')
    if not source.exists():
        return
    directory(source)
    if source.resolve() == destination.resolve() or source.resolve() in destination.resolve().parents:
        raise RuntimeError('Managed source and destination overlap')
    gid = destination.stat().st_gid
    for project in source.iterdir():
        directory(project)
        target = destination / project.name
        marker = target / ADOPTION_MARKER
        identity = str(project.absolute()).encode()
        if target.exists() or target.is_symlink():
            directory(target)
            if marker.is_symlink() or not marker.is_file() or marker.read_bytes() != identity:
                raise RuntimeError('Conflicting managed destination; source remains unchanged')
            protect_clone_parent(target, gid)
            continue
        with tempfile.TemporaryDirectory(dir=destination, prefix='.adoption-') as staging:
            staged = pathlib.Path(staging) / project.name
            copy_tree(project, staged, gid)
            staged.chmod(0o2750)
            fd = os.open(staged / ADOPTION_MARKER, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(identity)
            if target.exists() or target.is_symlink():
                raise RuntimeError('Managed destination changed during adoption')
            staged.rename(target)


if __name__ == '__main__':
    import sys
    try:
        adopt(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
    except Exception:
        raise SystemExit('Managed workspace adoption failed; original workspace retained.') from None
