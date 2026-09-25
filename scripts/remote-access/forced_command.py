#!/usr/bin/python3 -I
"""Forge-managed SSH control channel. No shell, execution, or admin dispatch."""
import base64
import json
import select
import time
import pathlib
import stat
import os
import pwd
import re
import socket
import struct
import sys
import uuid

SOCKET = '/run/forge-remote/channel/authority.sock'
BINDINGS = pathlib.Path('/var/lib/forge-remote/bindings')


def read_protected(path, owner, mode, limit):
    descriptor=os.open(path,os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info=os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=owner or stat.S_IMODE(info.st_mode)!=mode or info.st_size>limit:
            raise PermissionError('Unsafe channel credential metadata')
        return os.read(descriptor,limit+1).decode('ascii')
    finally: os.close(descriptor)


def require_binding_owner(path):
    for parent in path.parents:
        info=parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=0 or info.st_mode & 0o022:
            raise PermissionError('Unsafe binding directory')


def authenticated_binding(key_id):
    # The only accepted account keys are root-managed authorized_keys entries.
    # Their OpenSSH command= option supplies this digest; the client command does not.
    if not re.fullmatch(r'[0-9a-f]{64}',key_id):
        raise ValueError('Invalid key binding identity')
    digest=bytes.fromhex(key_id)
    fingerprint='SHA256:'+base64.b64encode(digest).decode('ascii').rstrip('=')
    record=BINDINGS/key_id
    require_binding_owner(record)
    binding=read_protected(record,0,0o640,256).strip().split(' ')
    if len(binding)!=4 or binding[3]!=fingerprint:
        raise ValueError('Key binding mismatch')
    return binding


def query(frame, timeout=3):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(timeout)
        client.connect(SOCKET)
        _, uid, _ = struct.unpack('3i', client.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        if uid != pwd.getpwnam('forge-control').pw_uid:
            raise PermissionError('Unexpected authority identity')
        client.sendall(frame.encode('ascii'))
        result = bytearray()
        while len(result) < 64:
            chunk = client.recv(64-len(result))
            if not chunk:
                break
            result.extend(chunk)
            if b'\n' in result:
                break
        return result.decode('ascii')


def read_request(descriptor=0, timeout=3):
    # SSH stdin must close after one JSON object; an open or trickling stream has
    # one overall deadline, and cannot keep a forced helper alive indefinitely.
    deadline = time.monotonic() + timeout
    payload = bytearray()
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([descriptor], [], [], remaining)[0]:
            raise TimeoutError('Pairing input deadline')
        chunk = os.read(descriptor, 5801 - len(payload))
        if not chunk: break
        payload.extend(chunk)
        if len(payload) > 5800: raise ValueError('Pairing input too large')
    if not isinstance(json.loads(payload.decode('utf-8')), dict):
        raise ValueError('Pairing object required')
    return bytes(payload)


def handle(binding, command):
    denied = ('DENIED\n', 1)
    if len(binding) != 4 or (binding[0], command) not in [('session', 'status'), ('session', 'confirm'), ('session', 'revoke'), ('session', 'reverse'), ('session', 'exec'), ('invitation', 'pair'), ('invitation', 'redeem')]:
        return denied
    try:
        if any(str(uuid.UUID(value)) != value for value in binding[1:3]):
            return denied
        if not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', binding[3]):
            return denied
        if command == 'exec':
            sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
            from execution_channel import execute
            return '', execute(binding,query)
        operation = command.upper()
        frame = operation + ' ' + ' '.join(binding[1:])
        if command in ('redeem', 'reverse'):
            frame += ' ' + base64.urlsafe_b64encode(read_request()).decode('ascii').rstrip('=')
        if len(frame) + 1 > 8192: return denied
        result = query(frame + '\n', timeout=90) if command in ('revoke', 'reverse') else query(frame + '\n')
        if command in ('redeem', 'reverse'):
            expected = 'PROVISIONING' if command == 'redeem' else 'ACTIVE'
            match = re.fullmatch(expected + r' ([0-9a-f-]{36})\n', result)
            if not match or str(uuid.UUID(match[1])) != match[1]: return denied
            return result, 0
        permitted = {'pair': ('PAIRING_ALLOWED\n',), 'confirm': ('ACTIVE\n',),
                     'status': ('ACTIVE\n', 'PROVISIONING\n'), 'revoke': ('REVOKING\n', 'REVOKED\n')}[command]
        if result not in permitted:
            return denied
        return result, 0
    except (OSError, ValueError, KeyError, RecursionError):
        return denied


if __name__ == '__main__':
    try:
        if len(sys.argv)!=2:
            raise ValueError('Missing key binding identity')
        binding=authenticated_binding(sys.argv[1])
        output, code = handle(binding, os.environ.get('SSH_ORIGINAL_COMMAND', ''))
    except (OSError,ValueError,KeyError):
        output,code='DENIED\n',1
    sys.stdout.write(output)
    sys.exit(code)
