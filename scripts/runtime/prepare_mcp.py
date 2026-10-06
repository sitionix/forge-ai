#!/usr/bin/python3 -I
"""Provision persistent main Forge protected material; never print secret contents."""
import base64
import binascii
import os
import pathlib
import re
import secrets
import stat



def validate_parents(root, uid):
    if not root.is_absolute() or root != pathlib.Path(os.path.normpath(root)):
        raise RuntimeError('Protected directory requires a canonical absolute path')
    for parent in reversed(root.parents):
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, uid):
            raise RuntimeError('Unsafe protected ancestor')
        # Sticky system temporary roots are allowed for disposable tests only;
        # the production Java/runtime verifier still requires trusted ancestors.
        if info.st_mode & 0o022 and not (info.st_uid == 0 and info.st_mode & stat.S_ISVTX):
            raise RuntimeError('Writable protected ancestor')


def validate_directory(root, uid):
    validate_parents(root, uid)
    if not root.exists() and not root.is_symlink():
        root.mkdir(mode=0o700)
        root.chmod(0o700)
        os.chown(root, uid, -1)
    info = root.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != uid or stat.S_IMODE(info.st_mode) != 0o700:
        raise RuntimeError('Protected directory requires trusted owner and mode 0700')


def read_existing(path, uid):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    except OSError:
        raise RuntimeError('Unsafe protected material') from None
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != uid or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1 or info.st_size > 65536:
            raise RuntimeError('Unsafe protected material')
        return stream.read(65537)


def create_once(path, value, uid, gid):
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        existing = read_existing(path, uid)
        if existing != value:
            raise RuntimeError('Concurrent protected material differs; repeat preparation') from None
        return
    with os.fdopen(fd, 'wb') as stream:
        os.fchmod(stream.fileno(), 0o600)
        os.fchown(stream.fileno(), uid, gid)
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def validate_key(value):
    try:
        lines = value.decode('ascii').splitlines()
        ring = dict(line.split('=', 1) for line in lines)
        active = ring['active']
        if len(ring) != len(lines) or not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', active):
            raise ValueError()
        if 'key.' + active not in ring:
            raise ValueError()
        for name, key in ring.items():
            if name == 'active':
                continue
            if not re.fullmatch(r'key\.[A-Za-z0-9_-]{1,40}', name) or len(base64.b64decode(key, validate=True)) != 32:
                raise ValueError()
    except (ValueError, KeyError, UnicodeError, binascii.Error):
        raise RuntimeError('Invalid existing key ring') from None


def prepare(root, control_uid, control_gid, database_credential):
    root = pathlib.Path(root)
    if not isinstance(database_credential, bytes) or not database_credential or len(database_credential) > 4096:
        raise RuntimeError('Database credential required')
    validate_directory(root, control_uid)
    paths = {'key': root / 'key-ring', 'database': root / 'database.secret'}
    existing = {name: read_existing(path, control_uid) for name, path in paths.items()}
    if existing['key'] is not None:
        validate_key(existing['key'])
    if existing['database'] is not None and existing['database'] != database_credential:
        raise RuntimeError('Configured database credential differs; rotation is not a startup operation')
    key = existing['key'] or b'active=main\nkey.main=' + base64.b64encode(secrets.token_bytes(32)) + b'\n'
    for name, value in {'key': key, 'database': database_credential}.items():
        if existing[name] is None:
            create_once(paths[name], value, control_uid, control_gid)
    return paths
