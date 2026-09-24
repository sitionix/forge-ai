import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

BASE = pathlib.Path(__file__).resolve().parents[1]

def load(name):
    spec = importlib.util.spec_from_file_location(name, BASE / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class ManagedSshTest(unittest.TestCase):
    def test_known_stage2_helper_upgrade_preserves_unknown_local_modifications(self):
        setup = load('install')
        with tempfile.TemporaryDirectory() as temp, patch.object(setup, 'require_root_owned'):
            library = pathlib.Path(temp)
            target = library/'forced-command'
            legacy = BASE/'tests'/'fixtures'/'stage2-forced-command.py'
            target.write_bytes(legacy.read_bytes()); target.chmod(0o755)
            with patch.object(setup, 'LIB', library):
                setup.install_forced_command(BASE/'forced_command.py')
                self.assertEqual((BASE/'forced_command.py').read_bytes(),target.read_bytes())
                setup.install_forced_command(BASE/'forced_command.py')
                target.write_text('unknown locally modified helper')
                with self.assertRaises(RuntimeError): setup.install_forced_command(BASE/'forced_command.py')
                self.assertEqual('unknown locally modified helper',target.read_text())

    def test_pairing_binding_can_only_query_its_own_pairing_gate(self):
        import uuid
        helper = load('forced_command')
        binding = ['invitation', str(uuid.uuid4()), str(uuid.uuid4()), 'SHA256:'+'A'*43]
        with patch.object(helper, 'query', return_value='PAIRING_ALLOWED\n') as query:
            self.assertEqual(('PAIRING_ALLOWED\n', 0), helper.handle(binding, 'pair'))
            query.assert_called_once_with('PAIR '+' '.join(binding[1:])+'\n')
            query.reset_mock()
            for command in ['status', 'exec id', 'pair extra', '', 'confirm']:
                self.assertEqual(('DENIED\n', 1), helper.handle(binding, command))
            query.assert_not_called()
        with patch.object(helper, 'query', return_value='ACTIVE\n'):
            self.assertEqual(('DENIED\n', 1), helper.handle(binding, 'pair'))

    def test_system_ssh_key_is_restricted_to_root_managed_forced_command(self):
        import base64
        import hashlib
        import os

        grants_module = load('invitation_supervisor')
        blob = b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20' + b'a' * 32
        key = 'ssh-ed25519 ' + base64.b64encode(blob).decode('ascii')
        key_id = hashlib.sha256(blob).hexdigest()
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / 'transport-home' / '.ssh').mkdir(parents=True)
            (root / 'bindings').mkdir()
            (root / 'transport-home').chmod(0o555)
            (root / 'transport-home' / '.ssh').chmod(0o755)
            (root / 'bindings').chmod(0o750)
            keys = root / 'transport-home' / '.ssh' / 'authorized_keys'
            keys.write_text('')
            keys.chmod(0o640)
            grants = grants_module.InvitationGrants(root, os.getuid(), os.getgid())
            grants.install('11111111-1111-4111-8111-111111111111',
                           '22222222-2222-4222-8222-222222222222', key)
            line = keys.read_text().strip()
            self.assertTrue(line.startswith(
                'restrict,command="/usr/libexec/forge-remote/forced-command ' + key_id + '" '))
            self.assertIn(key, line)

    def test_system_ssh_binding_rejects_client_selected_identity(self):
        helper = load('forced_command')
        with self.assertRaises((ValueError, PermissionError)):
            helper.authenticated_binding('not-a-key-identity')

    def test_system_ssh_setup_does_not_install_another_daemon(self):
        setup = load('install')
        self.assertFalse(hasattr(setup, 'sshd_config'))
        self.assertFalse(hasattr(setup, 'require_available_endpoint'))
        self.assertFalse(hasattr(setup, 'UNIT'))

    def test_dangling_host_public_key_symlink_cannot_create_an_unrelated_file(self):
        setup=load('install')
        with tempfile.TemporaryDirectory() as temp:
            host=pathlib.Path(temp)/'host_ed25519'
            outside=pathlib.Path(temp)/'unrelated'
            public=pathlib.Path(str(host)+'.pub')
            public.symlink_to(outside)
            with self.assertRaisesRegex(RuntimeError,'Host key conflict'):
                setup.ensure_host_key(host)
            self.assertFalse(host.exists())
            self.assertFalse(outside.exists())
            self.assertTrue(public.is_symlink())

    def test_generated_control_agent_runtime_has_explicit_loopback_bind(self):
        import os
        import subprocess
        renderer=BASE.parent/'systemd'/'render-units.sh'
        for user, supplied, expected in [('forge-control',None,'127.0.0.1'),
                                         ('forge-control','0.0.0.0','0.0.0.0'),
                                         ('ordinary-agent',None,None),
                                         ('ordinary-agent','192.0.2.10','192.0.2.10')]:
            with self.subTest(user=user,host=supplied), tempfile.TemporaryDirectory() as temp:
                environment=os.environ.copy()
                environment.pop('FORGE_AGENT_HOST',None)
                environment.update(FORGE_SYSTEMD_USER=user,FORGE_SYSTEMD_GROUP=user,
                                   FORGE_AGENT_DB_PASSWORD='synthetic-test-password',
                                   FORGE_AGENT_DB_URL='jdbc:postgresql://localhost:54329/fixture',
                                   FORGE_AGENT_DB_USERNAME='fixture')
                if supplied is not None: environment['FORGE_AGENT_HOST']=supplied
                output=pathlib.Path(temp)
                subprocess.run([str(renderer),str(output/'units'),str(output/'runtime.env')],
                               env=environment,check=True,capture_output=True,text=True)
                config=(output/'runtime.env').read_text()
                if expected is None:
                    self.assertNotIn('FORGE_AGENT_HOST=',config)
                else:
                    self.assertIn('FORGE_AGENT_HOST="'+expected+'"',config)
                self.assertIn('User='+user,(output/'units'/'forge-agent.service').read_text())
                self.assertNotIn('SERVER_ADDRESS=',config)

    def test_unprivileged_install_fails_before_side_effects(self):
        setup = load('install')
        with patch.object(setup.os,'geteuid',return_value=1234), patch.object(setup,'prepare') as prepare:
            with self.assertRaisesRegex(RuntimeError,'root'): setup.install()
            prepare.assert_not_called()

    def test_existing_unmanaged_file_is_not_overwritten(self):
        setup = load('install')
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp)/'config'
            path.write_text('operator content')
            with self.assertRaises(RuntimeError):
                setup.write_owned(path,b'new contents',0o600)
            self.assertEqual(path.read_text(),'operator content')

    def test_only_known_invitation_unit_can_upgrade_to_new_key_path(self):
        import hashlib
        setup = load('install')
        with tempfile.TemporaryDirectory() as temp, patch.object(setup, 'require_root_owned'):
            unit = pathlib.Path(temp) / 'forge-remote-invitations.service'
            unit.write_bytes(b'known old unit')
            unit.chmod(0o644)
            allowed = {hashlib.sha256(unit.read_bytes()).hexdigest()}
            setup.write_invitation_unit(b'new system SSH key path', unit, allowed)
            self.assertEqual(b'new system SSH key path', unit.read_bytes())
            unit.write_bytes(b'unknown local modification')
            with self.assertRaisesRegex(RuntimeError, 'Unknown invitation unit'):
                setup.write_invitation_unit(b'new system SSH key path', unit, allowed)
            self.assertEqual(b'unknown local modification', unit.read_bytes())

    def test_repeated_owned_file_install_keeps_bytes_and_inode(self):
        setup = load('install')
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp)/'owned'
            with patch.object(setup,'require_root_owned'):
                setup.write_owned(path,b'fixed configuration',0o600)
                inode=path.stat().st_ino
                setup.write_owned(path,b'fixed configuration',0o600)
                self.assertEqual(path.stat().st_ino,inode)
                self.assertEqual(path.stat().st_mode & 0o777,0o600)

    def test_symlink_destination_is_not_followed(self):
        setup=load('install')
        with tempfile.TemporaryDirectory() as temp:
            target=pathlib.Path(temp)/'target'
            target.write_text('preserved')
            link=pathlib.Path(temp)/'link'
            link.symlink_to(target)
            with self.assertRaises(RuntimeError): setup.write_owned(link,b'replacement',0o600)
            self.assertEqual(target.read_text(),'preserved')

    def test_remote_command_cannot_override_key_binding_or_execute(self):
        helper=load('forced_command')
        binding=['session','10000000-0000-4000-8000-000000000001',
                 '10000000-0000-4000-8000-000000000002','SHA256:'+'A'*43]
        for command in ['', 'sh', 'exec id', 'status other-session', 'confirm other-session', 'sftp']:
            with self.subTest(command=command), patch.object(helper,'query') as query:
                self.assertEqual(helper.handle(binding,command),('DENIED\n',1))
                query.assert_not_called()
        with patch.object(helper,'query',return_value='ACTIVE\n') as query:
            self.assertEqual(helper.handle(binding,'status'),('ACTIVE\n',0))
            query.assert_called_once_with('STATUS '+' '.join(binding[1:])+'\n')

    def test_pairing_and_unavailable_authority_never_open_a_shell(self):
        helper=load('forced_command')
        binding=['session','10000000-0000-4000-8000-000000000001',
                 '10000000-0000-4000-8000-000000000002','SHA256:'+'A'*43]
        with patch.object(helper,'query',side_effect=TimeoutError('synthetic secret')):
            self.assertEqual(helper.handle(binding,'status'),('DENIED\n',1))
        binding[0]='pairing'
        with patch.object(helper,'query') as query:
            self.assertEqual(helper.handle(binding,'status'),('DENIED\n',1))
            query.assert_not_called()

    def test_root_managed_key_command_selects_matching_binding(self):
        import base64
        import hashlib
        helper=load('forced_command')
        blob=b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20'+bytes(32)
        fingerprint='SHA256:'+base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip('=')
        binding=['session','10000000-0000-4000-8000-000000000001',
                 '10000000-0000-4000-8000-000000000002',fingerprint]
        with tempfile.TemporaryDirectory() as temp:
            bindings=pathlib.Path(temp)/'bindings'
            bindings.mkdir()
            record=bindings/hashlib.sha256(blob).hexdigest()
            record.write_text(' '.join(binding)+'\n')
            record.chmod(0o640)
            with patch.object(helper,'BINDINGS',bindings), patch.object(helper,'require_binding_owner'), patch.object(helper,'read_protected',side_effect=lambda path,owner,mode,limit: pathlib.Path(path).read_text()):
                self.assertEqual(helper.authenticated_binding(hashlib.sha256(blob).hexdigest()),binding)
                record.write_text(' '.join(binding[:-1]+['SHA256:'+'A'*43])+'\n')
                with self.assertRaises(ValueError): helper.authenticated_binding(hashlib.sha256(blob).hexdigest())
                with self.assertRaises(FileNotFoundError): helper.authenticated_binding('f'*64)

    def test_unexpected_authority_response_fails_closed(self):
        helper=load('forced_command')
        binding=['session','10000000-0000-4000-8000-000000000001',
                 '10000000-0000-4000-8000-000000000002','SHA256:'+'A'*43]
        for response in ['REVOKED\n','ACTIVE\nsecret\n','','ACTIVE']:
            with patch.object(helper,'query',return_value=response):
                self.assertEqual(helper.handle(binding,'status'),('DENIED\n',1))

if __name__=='__main__': unittest.main()
