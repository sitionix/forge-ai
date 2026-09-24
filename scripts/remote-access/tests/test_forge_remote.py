import importlib.util
import io
import json
import pathlib
import socket
import struct
import threading
import unittest
from unittest.mock import patch


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'forge-remote'
spec = importlib.util.spec_from_file_location('forge_remote', SCRIPT)
if spec is None:
    # Extension-free executable is loaded through the normal source loader.
    from importlib.machinery import SourceFileLoader
    spec = importlib.util.spec_from_loader('forge_remote', SourceFileLoader('forge_remote', str(SCRIPT)))
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)


def frame(conn, kind, payload=b''):
    conn.sendall(kind + struct.pack('!I', len(payload)) + payload)


def exact(conn, length):
    value = b''
    while len(value) < length:
        part = conn.recv(length - len(value))
        if not part:
            raise EOFError()
        value += part
    return value


class ForgeRemoteTest(unittest.TestCase):
    def test_literal_argv_stdin_binary_streams_and_exit_code(self):
        client, server = socket.socketpair()
        received = {}

        def peer():
            with server:
                size = struct.unpack('!I', exact(server, 4))[0]
                received['request'] = json.loads(exact(server, size))
                frame(server, b'A')
                kind, size = exact(server, 1), struct.unpack('!I', exact(server, 4))[0]
                received['stdin'] = (kind, exact(server, size))
                received['end'] = exact(server, 5)
                frame(server, b'O', b'out\x00')
                frame(server, b'R', b'err\xff')
                frame(server, b'X', struct.pack('!i', 7))

        thread = threading.Thread(target=peer, daemon=True); thread.start()
        stdin, stdout, stderr = io.BytesIO(b'in\n'), io.BytesIO(), io.BytesIO()
        with patch.object(remote, 'connect', return_value=client):
            result = remote.run(['exec', '--session', '10000000-0000-4000-8000-000000000001',
                                 '--cwd', '/workspace/a b', '--', '/bin/echo', "a 'b' $HOME $(touch /tmp/no)"] ,
                                stdin, stdout, stderr)
        thread.join(timeout=2)
        self.assertEqual(7, result)
        self.assertEqual(['/bin/echo', "a 'b' $HOME $(touch /tmp/no)"], received['request']['argv'])
        self.assertEqual({'sessionId', 'argv', 'cwd', 'timeoutSeconds'}, set(received['request']))
        self.assertEqual('/workspace/a b', received['request']['cwd'])
        self.assertEqual((b'I', b'in\n'), received['stdin'])
        self.assertEqual(b'E\0\0\0\0', received['end'])
        self.assertEqual(b'out\x00', stdout.getvalue())
        self.assertEqual(b'err\xff', stderr.getvalue())

    def test_unavailable_session_never_runs_any_local_command(self):
        client, server = socket.socketpair()
        def peer():
            with server:
                size = struct.unpack('!I', exact(server, 4))[0]
                exact(server, size)
                frame(server, b'F', b'Remote execution unavailable')
        thread = threading.Thread(target=peer, daemon=True); thread.start()
        with patch.object(remote, 'connect', return_value=client), patch('subprocess.run') as local:
            result = remote.run(['exec', '--session', '10000000-0000-4000-8000-000000000001',
                                 '--cwd', '/workspace', '--', '/bin/true'], io.BytesIO(), io.BytesIO(), io.BytesIO())
        thread.join(timeout=2)
        self.assertNotEqual(0, result)
        local.assert_not_called()

    def test_missing_command_rejected_before_socket(self):
        with patch.object(remote, 'connect') as connect:
            with self.assertRaises(SystemExit):
                remote.run(['exec', '--session', '10000000-0000-4000-8000-000000000001',
                            '--cwd', '/workspace'], io.BytesIO(), io.BytesIO(), io.BytesIO())
        connect.assert_not_called()


if __name__ == '__main__':
    unittest.main()
