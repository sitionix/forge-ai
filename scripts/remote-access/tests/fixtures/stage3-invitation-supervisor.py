#!/usr/bin/python3 -I
"""Narrow root helper: only forge-control can install/remove invitation public-key grants."""
import base64
import hashlib
import os
import pathlib
import pwd
import socket
import stat
import struct
import tempfile
import uuid

STATE = pathlib.Path('/var/lib/forge-remote')
SOCKET = pathlib.Path('/run/forge-remote/admin/invitations.sock')


def canonical_uuid(value):
    if str(uuid.UUID(value)) != value:
        raise ValueError('Invalid identity')
    return value


def public_key(value):
    parts = value.split(' ')
    if len(parts) != 2 or parts[0] != 'ssh-ed25519':
        raise ValueError('Invalid key')
    blob = base64.b64decode(parts[1], validate=True)
    if len(blob) != 51 or blob[:19] != b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20' or base64.b64encode(blob).decode() != parts[1]:
        raise ValueError('Invalid key')
    return hashlib.sha256(blob).digest()


class InvitationGrants:
    def __init__(self, root, owner, group):
        self.root, self.owner, self.group = root, owner, group
        for directory in [root, root/'authorized', root/'bindings']:
            info = directory.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != owner or info.st_mode & 0o022:
                raise ValueError('Unsafe grant directory')

    def _read(self, path):
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            info = os.fstat(descriptor)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != self.owner or info.st_mode & 0o022 or info.st_size > 1048576:
                raise ValueError('Unsafe grant file')
            return os.read(descriptor, 1048577).decode('ascii')
        finally:
            os.close(descriptor)

    def _atomic(self, path, content):
        fd, temporary = tempfile.mkstemp(prefix='.invitation-', dir=path.parent)
        try:
            os.fchmod(fd, 0o640)
            os.fchown(fd, self.owner, self.group)
            with os.fdopen(fd, 'w', encoding='ascii') as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(directory)
            finally: os.close(directory)
        finally:
            if os.path.exists(temporary): os.unlink(temporary)

    def _write_keys(self, text):
        self._atomic(self.root/'authorized'/'keys', text)

    def _binding(self, grantor, invitation, key):
        canonical_uuid(grantor)
        canonical_uuid(invitation)
        digest = public_key(key)
        fingerprint = 'SHA256:' + base64.b64encode(digest).decode().rstrip('=')
        record = self.root/'bindings'/digest.hex()
        expected = 'invitation '+grantor+' '+invitation+' '+fingerprint+'\n'
        line = 'restrict '+key+' forge-invitation:'+grantor+':'+invitation+'\n'
        try: actual = self._read(record)
        except FileNotFoundError: actual = None
        if actual is not None and actual != expected:
            raise ValueError('Foreign key binding')
        return record, expected, actual, line

    def install(self, grantor, invitation, key):
        record, expected, actual, line = self._binding(grantor, invitation, key)
        keys = self._read(self.root/'authorized'/'keys')
        if any(key in entry and entry+'\n' != line for entry in keys.splitlines()):
            raise ValueError('Key already has another grant')
        if actual is None: self._atomic(record, expected)
        if line not in keys.splitlines(keepends=True):
            self._write_keys(keys + ('' if not keys or keys.endswith('\n') else '\n') + line)

    def remove(self, grantor, invitation, key):
        record, expected, actual, line = self._binding(grantor, invitation, key)
        keys = self._read(self.root/'authorized'/'keys')
        if line in keys.splitlines(keepends=True):
            self._write_keys(''.join(entry for entry in keys.splitlines(keepends=True) if entry != line))
        if actual is not None: record.unlink()


def dispatch(peer_uid, control_uid, frame, grants, host_public):
    if peer_uid != control_uid: return 'DENIED\n'
    try:
        if not frame.endswith('\n') or frame.count('\n') != 1 or len(frame) > 1024:
            return 'DENIED\n'
        fields = frame[:-1].split(' ')
        if fields == ['HOST']:
            value = ' '.join(grants._read(host_public).split()[:2])
            public_key(value)
            return value+'\n'
        if len(fields) != 5: return 'DENIED\n'
        if fields[0] == 'INSTALL': grants.install(fields[1], fields[2], ' '.join(fields[3:]))
        elif fields[0] == 'REMOVE': grants.remove(fields[1], fields[2], ' '.join(fields[3:]))
        else: return 'DENIED\n'
        return 'OK\n'
    except (OSError, ValueError, UnicodeError):
        return 'DENIED\n'


def main():
    if os.geteuid() != 0: raise RuntimeError('Privileged supervisor requires root')
    control = pwd.getpwnam('forge-control')
    peer = pwd.getpwnam('forge-ssh')
    grants = InvitationGrants(STATE, 0, peer.pw_gid)
    for parent in SOCKET.parents:
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise RuntimeError('Unsafe supervisor directory')
    # systemd owns this singleton's runtime directory; never unlink an unknown socket.
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
        listener.bind(str(SOCKET))
        os.chown(SOCKET, 0, control.pw_gid)
        os.chmod(SOCKET, 0o660)
        listener.listen(8)
        while True:
            connection, _ = listener.accept()
            with connection:
                connection.settimeout(2)
                try:
                    _, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                    if uid != control.pw_uid:
                        connection.sendall(b'DENIED\n')
                        continue
                    frame = bytearray()
                    while len(frame) <= 1024 and not frame.endswith(b'\n'):
                        chunk = connection.recv(1025-len(frame))
                        if not chunk: break
                        frame.extend(chunk)
                    result = dispatch(uid, control.pw_uid, frame.decode('ascii'), grants, pathlib.Path('/etc/forge-remote/host_ed25519.pub'))
                    connection.sendall(result.encode('ascii'))
                except (OSError, ValueError, UnicodeError): pass


if __name__ == '__main__': main()
