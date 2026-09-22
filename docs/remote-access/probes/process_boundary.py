"""Actual user-systemd cgroup probe. No root, no custom cgroup writes.
Does not prove privileged supervisor isolation. Requires working user systemd.
"""
import os
from pathlib import Path
import subprocess as sp
import tempfile
import time
import uuid


def call(*args):
    return sp.run(args, capture_output=True, text=True, timeout=15)


def require(condition, name):
    if not condition:
        raise AssertionError(name)
    print('PASS ' + name, flush=True)


prefix = 'forge-stage0-' + uuid.uuid4().hex[:12]
units = [prefix + '-a.service', prefix + '-b.service']
with tempfile.TemporaryDirectory(prefix='forge-stage0-') as directory:
    parent = Path(directory)
    script = parent / 'workload.sh'
    script.write_text('''#!/bin/sh
set -eu
echo $$ > "$1/parent"
sleep 300 &
echo $! > "$1/child"
setsid sleep 300 &
echo $! > "$1/setsid"
wait
''')
    try:
        for i, unit in enumerate(units):
            folder = parent / str(i)
            folder.mkdir()
            r = call('systemd-run', '--user', '--unit=' + unit, '--property=KillMode=control-group',
                     '--property=TimeoutStopSec=2s', '--property=SendSIGKILL=yes',
                     '/bin/sh', str(script), str(folder))
            if r.returncode:
                raise RuntimeError('NOT READY: user systemd unit creation failed: ' + r.stderr.strip())
        for _ in range(100):
            if all((parent / str(i) / 'setsid').exists() for i in range(2)):
                break
            time.sleep(.05)
        pids = [[int((parent / str(i) / name).read_text()) for name in ['parent','child','setsid']] for i in range(2)]
        groups = []
        for unit, session_pids in zip(units, pids):
            r = call('systemctl', '--user', 'show', unit, '--property=ControlGroup', '--value')
            require(r.returncode == 0 and r.stdout.strip(), 'systemd exposes managed cgroup')
            group = r.stdout.strip()
            groups.append(group)
            for pid in session_pids:
                require(Path('/proc', str(pid), 'cgroup').read_text().strip() == '0::' + group,
                        'parent/child/setsid belongs to session cgroup')
        require(groups[0] != groups[1], 'sessions have separate cgroups')
        require(call('systemctl','--user','stop',units[0]).returncode == 0, 'stop session A acknowledged')
        for _ in range(100):
            if all(not Path('/proc',str(pid)).exists() for pid in pids[0]):
                break
            time.sleep(.05)
        require(all(not Path('/proc',str(pid)).exists() for pid in pids[0]), 'A parent and descendants terminated')
        require(all(Path('/proc',str(pid)).exists() for pid in pids[1]), 'B parent and descendants remain alive')
        print('PARTIAL_ONLY: same-user mechanics; no privileged isolation or Forge revoke', flush=True)
    finally:
        for unit in units:
            call('systemctl', '--user', 'stop', unit)
            call('systemctl', '--user', 'reset-failed', unit)
