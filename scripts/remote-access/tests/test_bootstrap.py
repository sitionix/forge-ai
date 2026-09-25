import os
import pathlib
import socket
import struct
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import bootstrap


class BootstrapTest(unittest.TestCase):
    def exchange(self, request, expected_uid, operation):
        client, server = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            client.sendall(request)
            client.shutdown(socket.SHUT_WR)
            bootstrap.handle_client(server, expected_uid, operation)
            return client.recv(128)
        finally:
            client.close()
            server.close()

    def test_only_local_operator_can_start_fixed_setup(self):
        calls = []
        response = self.exchange(b'ENABLE\n', os.getuid() + 1, calls.append)
        self.assertEqual(b'DENIED\n', response)
        self.assertEqual([], calls)

    def test_foreign_uid_is_denied_before_reading_request(self):
        class ForeignConnection:
            response = b''
            def settimeout(self, _): pass
            def getsockopt(self, *_): return struct.pack('3i', 1, os.getuid() + 1, os.getgid())
            def recv(self, _): raise AssertionError('foreign caller must not hold the listener')
            def sendall(self, value): self.response += value
        connection = ForeignConnection()
        bootstrap.handle_client(connection, os.getuid(), lambda _: self.fail('setup called'))
        self.assertEqual(b'DENIED\n', connection.response)

    def test_unknown_or_oversized_operation_cannot_execute(self):
        calls = []
        for request in (b'EXEC /bin/sh\n', b'ENABLE extra\n', b'ENABLE\n' + b'x' * 64):
            self.assertEqual(b'DENIED\n', self.exchange(request, os.getuid(), calls.append))
        self.assertEqual([], calls)

    def test_enable_starts_only_fixed_setup_operation(self):
        calls = []
        self.assertEqual(b'PREPARING\n', self.exchange(b'ENABLE\n', os.getuid(), calls.append))
        self.assertEqual(['ENABLE'], calls)

    def test_fragmented_trailing_bytes_cannot_turn_into_enable(self):
        class FragmentedConnection:
            def __init__(self):
                self.fragments = iter((b'ENABLE\n', b'EXEC\n', b''))
                self.response = b''
            def settimeout(self, _): pass
            def getsockopt(self, *_): return struct.pack('3i', 1, os.getuid(), os.getgid())
            def recv(self, _): return next(self.fragments)
            def sendall(self, value): self.response += value
        connection = FragmentedConnection()
        calls = []
        bootstrap.handle_client(connection, os.getuid(), calls.append)
        self.assertEqual(b'DENIED\n', connection.response)
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
