import base64
import os
import pathlib
import unittest
from unittest.mock import patch
from test_managed_ssh import load, BASE
from test_invitation_grants import InvitationGrantsTest

G = '10000000-0000-4000-8000-000000000001'
S = '10000000-0000-4000-8000-000000000002'
FP = 'SHA256:'+'A'*43

class Stage4HelperTest(unittest.TestCase):
    def test_redeem_routes_only_authenticated_invitation_and_safe_reply(self):
        h = load('forced_command')
        payload = b'{"sessionId":"'+S.encode()+b'"}'
        with patch.object(h, 'read_request', return_value=payload), patch.object(h, 'query', return_value='PROVISIONING '+S+'\n') as query:
            self.assertEqual(('PROVISIONING '+S+'\n', 0), h.handle(['invitation',G,S,FP], 'redeem'))
            query.assert_called_once_with('REDEEM '+G+' '+S+' '+FP+' '+base64.urlsafe_b64encode(payload).decode().rstrip('=')+'\n')
        for response in ['ACTIVE\n', 'PROVISIONING '+S.upper()+'X\n', 'PROVISIONING '+S+'\nsecret', 'PROVISIONING '+S]:
            with patch.object(h, 'read_request', return_value=payload), patch.object(h, 'query', return_value=response):
                self.assertEqual(('DENIED\n',1),h.handle(['invitation',G,S,FP],'redeem'))

    def test_confirm_accepts_only_active_and_cannot_override_binding(self):
        h=load('forced_command')
        with patch.object(h,'query',return_value='ACTIVE\n') as query:
            self.assertEqual(('ACTIVE\n',0),h.handle(['session',G,S,FP],'confirm'))
            query.assert_called_once_with('CONFIRM '+G+' '+S+' '+FP+'\n')
            query.reset_mock()
            for kind,command in [('invitation','confirm'),('session','redeem'),('session','confirm '+G),('session','exec id')]:
                self.assertEqual(('DENIED\n',1),h.handle([kind,G,S,FP],command))
            query.assert_not_called()
        with patch.object(h,'query',return_value='PROVISIONING\n'):
            self.assertEqual(('DENIED\n',1),h.handle(['session',G,S,FP],'confirm'))

    def test_reverse_uses_authenticated_session_binding_and_requires_exact_success(self):
        h=load('forced_command')
        payload=b'{"pairId":"'+S.encode()+b'","token":"secret"}'
        frame='REVERSE '+G+' '+S+' '+FP+' '+base64.urlsafe_b64encode(payload).decode().rstrip('=')+'\n'
        with patch.object(h,'read_request',return_value=payload), patch.object(h,'query',return_value='ACTIVE '+S+'\n') as query:
            self.assertEqual(('ACTIVE '+S+'\n',0),h.handle(['session',G,S,FP],'reverse'))
            query.assert_called_once_with(frame,timeout=90)
        with patch.object(h,'read_request',return_value=payload), patch.object(h,'query') as query:
            self.assertEqual(('DENIED\n',1),h.handle(['invitation',G,S,FP],'reverse'))
            query.assert_not_called()
        for reply in ['ACTIVE\n','ACTIVE '+S+'\nextra','PROVISIONING '+S+'\n']:
            with patch.object(h,'read_request',return_value=payload),patch.object(h,'query',return_value=reply):
                self.assertEqual(('DENIED\n',1),h.handle(['session',G,S,FP],'reverse'))

    def test_input_is_bounded_in_bytes_and_time_and_requires_json_object(self):
        h=load('forced_command')
        for payload in [b'[]',b'bad',b'{}'+b' '*6000]:
            r,w=os.pipe()
            try:
                os.write(w,payload);os.close(w);w=None
                with self.assertRaises(ValueError): h.read_request(r,timeout=.05)
            finally:
                os.close(r)
                if w is not None: os.close(w)
        r,w=os.pipe()
        try:
            os.write(w,b'{}')
            with self.assertRaises(TimeoutError): h.read_request(r,timeout=.05)
        finally: os.close(r);os.close(w)

    def test_supervisor_upgrade_requires_removed_runtime_directory_before_any_helper_write(self):
        import tempfile
        setup=load('install')
        with tempfile.TemporaryDirectory() as temp, patch.object(setup,'require_root_owned'):
            root=pathlib.Path(temp)
            library=root/'lib';library.mkdir()
            runtime=root/'run';runtime.mkdir()
            admin=runtime/'admin';admin.mkdir()
            target=library/'invitation-supervisor'
            old=(BASE/'tests'/'fixtures'/'stage3-invitation-supervisor.py').read_bytes()
            target.write_bytes(old);target.chmod(0o755)
            with patch.object(setup,'LIB',library), patch.object(setup,'RUN',runtime):
                with self.assertRaisesRegex(RuntimeError,'stop managed invitation supervisor'):
                    setup.install_invitation_supervisor(BASE/'invitation_supervisor.py')
                self.assertEqual(old,target.read_bytes())
                with patch.object(setup,'directory') as directory:
                    with self.assertRaisesRegex(RuntimeError,'stop managed invitation supervisor'):
                        setup.prepare()
                    directory.assert_not_called()
                admin.rmdir()
                setup.install_invitation_supervisor(BASE/'invitation_supervisor.py')
                self.assertEqual((BASE/'invitation_supervisor.py').read_bytes(),target.read_bytes())
                admin.mkdir()
                setup.install_invitation_supervisor(BASE/'invitation_supervisor.py')

    def test_exact_stage3_artifacts_upgrade_and_unknown_bytes_conflict(self):
        import tempfile
        setup=load('install')
        with tempfile.TemporaryDirectory() as temp, patch.object(setup,'require_root_owned'), patch.object(setup,'LIB',pathlib.Path(temp)), patch.object(setup,'RUN',pathlib.Path(temp)/'run'):
            for name,source,fixture,installer in [('forced-command','forced_command.py','stage3-forced-command.py',setup.install_forced_command),('invitation-supervisor','invitation_supervisor.py','stage3-invitation-supervisor.py',setup.install_invitation_supervisor)]:
                target=pathlib.Path(temp)/name
                target.write_bytes((BASE/'tests'/'fixtures'/fixture).read_bytes());target.chmod(0o755)
                installer(BASE/source)
                self.assertEqual((BASE/source).read_bytes(),target.read_bytes())
                target.write_text('unknown')
                with self.assertRaises(RuntimeError): installer(BASE/source)
                self.assertEqual('unknown',target.read_text())

class Stage4GrantsTest(InvitationGrantsTest):
    def test_session_grants_are_idempotent_immutable_and_kind_isolated(self):
        def dispatch(op,grantor=None):
            return self.supervisor.dispatch(1000,1000,op+' '+(grantor or self.grantor)+' '+self.invitation+' '+self.key+'\n',self.grants,self.root/'host.pub')
        self.assertEqual('OK\n',dispatch('SESSION_INSTALL'))
        before=self.keys.read_bytes()
        self.assertEqual('OK\n',dispatch('SESSION_INSTALL'))
        self.assertEqual(before,self.keys.read_bytes())
        self.assertIn('restrict,command="/usr/libexec/forge-remote/forced-command ',before.decode())
        self.assertIn(self.key+' forge-session:',before.decode())
        self.assertTrue(next((self.root/'bindings').iterdir()).read_text().startswith('session '))
        self.assertEqual('DENIED\n',dispatch('REMOVE'))
        self.assertEqual('DENIED\n',dispatch('SESSION_REMOVE',G))
        self.assertEqual(before,self.keys.read_bytes())
        self.assertEqual('OK\n',dispatch('SESSION_REMOVE'))
        self.assertEqual('OK\n',dispatch('SESSION_REMOVE'))
        self.assertEqual('existing unrelated grant\n',self.keys.read_text())
