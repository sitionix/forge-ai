import base64
import importlib.util
import os
import pathlib
import tempfile
import unittest
import uuid
from unittest.mock import patch

BASE = pathlib.Path(__file__).resolve().parents[1]

def load():
    spec = importlib.util.spec_from_file_location('invitation_supervisor', BASE/'invitation_supervisor.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class InvitationGrantsTest(unittest.TestCase):
    def setUp(self):
        self.supervisor = load()
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        (self.root/'bindings').mkdir()
        (self.root/'authorized').mkdir()
        (self.root/'authorized'/'keys').write_text('existing unrelated grant\n')
        (self.root/'authorized'/'keys').chmod(0o640)
        (self.root/'bindings').chmod(0o750)
        (self.root/'authorized').chmod(0o750)
        self.grantor = str(uuid.uuid4())
        self.invitation = str(uuid.uuid4())
        self.key = 'ssh-ed25519 '+base64.b64encode(b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20'+bytes(range(32))).decode()
        self.grants = self.supervisor.InvitationGrants(self.root, os.getuid(), os.getgid())

    def test_install_and_remove_preserve_unrelated_grants_and_are_idempotent(self):
        self.grants.install(self.grantor, self.invitation, self.key)
        installed = (self.root/'authorized'/'keys').read_bytes()
        self.grants.install(self.grantor, self.invitation, self.key)
        self.assertEqual(installed, (self.root/'authorized'/'keys').read_bytes())
        self.assertIn(self.key.encode(), installed)
        self.assertEqual(1, len(list((self.root/'bindings').iterdir())))
        self.grants.remove(self.grantor, self.invitation, self.key)
        self.grants.remove(self.grantor, self.invitation, self.key)
        self.assertEqual('existing unrelated grant\n', (self.root/'authorized'/'keys').read_text())
        self.assertEqual([], list((self.root/'bindings').iterdir()))

    def test_foreign_binding_or_symlink_is_never_replaced(self):
        self.grants.install(self.grantor, self.invitation, self.key)
        before = (self.root/'authorized'/'keys').read_bytes()
        with self.assertRaises(ValueError):
            self.grants.remove(self.grantor, str(uuid.uuid4()), self.key)
        self.assertEqual(before, (self.root/'authorized'/'keys').read_bytes())
        keys = self.root/'authorized'/'keys'
        keys.unlink()
        target = self.root/'target'
        target.write_text('untouched')
        keys.symlink_to(target)
        with self.assertRaises((OSError, ValueError)):
            self.grants.install(self.grantor, self.invitation, self.key)
        self.assertEqual('untouched', target.read_text())

    def test_partial_install_can_be_compensated_without_touching_other_grants(self):
        with patch.object(self.grants, '_write_keys', side_effect=OSError('synthetic failure')):
            with self.assertRaises(OSError):
                self.grants.install(self.grantor, self.invitation, self.key)
        self.grants.remove(self.grantor, self.invitation, self.key)
        self.assertEqual('existing unrelated grant\n', (self.root/'authorized'/'keys').read_text())
        self.assertEqual([], list((self.root/'bindings').iterdir()))

    def test_peer_uid_and_unknown_operations_are_denied_before_filesystem_changes(self):
        for uid, frame in [(1001, 'HOST\n'), (1000, 'EXEC id\n'), (1000, 'INSTALL bad\n')]:
            self.assertEqual('DENIED\n', self.supervisor.dispatch(uid, 1000, frame, self.grants, self.root/'host.pub'))
        self.assertEqual('existing unrelated grant\n', (self.root/'authorized'/'keys').read_text())

if __name__ == '__main__': unittest.main()
