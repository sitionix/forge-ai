"""Disposable real-sshd assertions; synthetic material only, never prints keys.
Run in the accompanying container with --network none, without mounts/privilege.
This does NOT test Forge authority, systemd, or production workload isolation.
"""
import os
import pathlib
import socket
import subprocess as sp
import time


def run(*args, **kwargs):
    return sp.run(args, capture_output=True, text=True, timeout=12, **kwargs)


def check(condition, name):
    if not condition:
        raise AssertionError(name)
    print('PASS ' + name, flush=True)


os.makedirs('/run/sshd', exist_ok=True)
os.makedirs('/probe', mode=0o755)
for user in ['pairing', 'session', 'workload']:
    sp.run(['useradd', '-m', '-s', '/bin/sh', user], check=True)
    # Unlock only the disposable accounts; password authentication remains disabled.
    sp.run(['usermod', '-p', '*', user], check=True)
for key in ['host', 'wrong', 'pairing', 'session']:
    sp.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', '/probe/' + key], check=True)
for role in ['pairing', 'session']:
    helper = pathlib.Path('/probe/' + role + '-helper')
    helper.write_text('#!/bin/sh\nprintf "' + role.upper() + '_ONLY\\n"\n')
    helper.chmod(0o755)
    pathlib.Path('/probe/' + role + '-authorized').write_text(
        'restrict,command="' + str(helper) + '" ' + pathlib.Path('/probe/' + role + '.pub').read_text())
    pathlib.Path('/probe/' + role + '-authorized').chmod(0o644)
pathlib.Path('/probe/sshd.conf').write_text('''
Port 22222
ListenAddress 127.0.0.1
HostKey /probe/host
PidFile /probe/sshd.pid
AuthorizedKeysFile /probe/%u-authorized
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
PermitRootLogin no
AllowUsers pairing session
PermitTTY no
DisableForwarding yes
PermitUserEnvironment no
PermitUserRC no
LogLevel ERROR
''')
for source, target in [('host', 'known'), ('wrong', 'wrong-known')]:
    key = pathlib.Path('/probe/' + source + '.pub').read_text().split()
    pathlib.Path('/probe/' + target).write_text('[127.0.0.1]:22222 ' + ' '.join(key[:2]) + '\n')
sshd = sp.Popen(['/usr/sbin/sshd', '-D', '-e', '-f', '/probe/sshd.conf'], stdout=sp.DEVNULL, stderr=sp.PIPE)
try:
    for _ in range(50):
        try:
            with socket.create_connection(('127.0.0.1', 22222), timeout=.1):
                break
        except OSError:
            time.sleep(.1)
    def ssh(role, *args, known='known'):
        return run('ssh', '-F', '/dev/null', '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
                   '-o', 'IdentityAgent=none', '-o', 'StrictHostKeyChecking=yes',
                   '-o', 'UserKnownHostsFile=/probe/' + known, '-o', 'GlobalKnownHostsFile=/dev/null',
                   '-o', 'PasswordAuthentication=no', '-o', 'KbdInteractiveAuthentication=no',
                   '-o', 'ControlMaster=no', '-o', 'ControlPath=none', '-i', '/probe/' + role,
                   '-p', '22222', *args, role + '@127.0.0.1', 'touch /tmp/escaped')
    for role in ['pairing', 'session']:
        r = ssh(role)
        check(r.returncode == 0 and r.stdout.strip() == role.upper() + '_ONLY', role + ' forced command')
    check(not pathlib.Path('/tmp/escaped').exists(), 'requested shell command did not execute')
    r = ssh('pairing', known='wrong-known')
    check(r.returncode == 255 and 'Host key verification failed' in r.stderr, 'wrong pinned host key denied')
    r = ssh('pairing', '-tt')
    check('PTY allocation request failed' in r.stderr, 'PTY denied')
    r = ssh('pairing', '-o', 'ExitOnForwardFailure=yes', '-R', '22333:127.0.0.1:22222')
    check(r.returncode == 255 and 'forwarding failed' in r.stderr, 'remote forwarding denied')
    r = ssh('pairing', '-W', '127.0.0.1:22222')
    check(r.returncode == 255 and 'administratively prohibited' in r.stderr, 'direct TCP forwarding denied')
    # Synthetic protected control socket: real Unix permission enforcement.
    os.makedirs('/probe/control', mode=0o700)
    pathlib.Path('/probe/control/canary').write_text('synthetic-not-a-credential')
    listener = socket.socket(socket.AF_UNIX)
    listener.bind('/probe/control/supervisor.sock')
    listener.listen(1)
    for label, command in [
        ('authorization write denied', ['sh', '-c', 'echo changed >> /probe/session-authorized']),
        ('helper write denied', ['sh', '-c', 'echo changed >> /probe/session-helper']),
        ('control canary read denied', ['cat', '/probe/control/canary']),
        ('supervisor socket denied', ['python3', '-c', 'import socket; s=socket.socket(socket.AF_UNIX); s.connect("/probe/control/supervisor.sock")'])]:
        r = run('runuser', '-u', 'workload', '--', *command)
        check(r.returncode != 0 and 'Permission denied' in r.stderr, label)
    listener.close()
    print('PARTIAL_ONLY: no Forge authority/admin API or systemd tested', flush=True)
finally:
    sshd.terminate()
    sshd.wait(timeout=5)
