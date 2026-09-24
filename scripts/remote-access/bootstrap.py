#!/usr/bin/python3 -I
"""Fixed local-operator gate for starting the reviewed Remote Access setup unit."""

import os
import pwd
import socket
import struct
import subprocess
import sys


SETUP_UNIT = 'forge-remote-setup.service'


def handle_client(connection, operator_uid, operation):
    connection.settimeout(3)
    try:
        credentials = connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12)
        _, uid, _ = struct.unpack('3i', credentials)
        if uid != operator_uid:
            connection.sendall(b'DENIED\n')
            return
        request = bytearray()
        while len(request) < 65:
            fragment = connection.recv(65 - len(request))
            if not fragment:
                break
            request.extend(fragment)
        if request != b'ENABLE\n':
            connection.sendall(b'DENIED\n')
            return
        result = operation('ENABLE')
        connection.sendall((result or 'PREPARING').encode('ascii') + b'\n')
    except (OSError, UnicodeError, ValueError, subprocess.SubprocessError):
        try:
            connection.sendall(b'FAILED\n')
        except OSError:
            pass


def start_setup(_):
    subprocess.run(['/usr/bin/systemctl', 'start', '--no-block', SETUP_UNIT],
                   stdin=subprocess.DEVNULL, capture_output=True,
                   check=True, timeout=10)
    return 'PREPARING'


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        return 1
    operator_uid = pwd.getpwnam(sys.argv[1]).pw_uid
    listener = socket.socket(fileno=3)
    try:
        while True:
            connection, _ = listener.accept()
            with connection:
                handle_client(connection, operator_uid, start_setup)
    finally:
        listener.close()


if __name__ == '__main__':
    sys.exit(main())
