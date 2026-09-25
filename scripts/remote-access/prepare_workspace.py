#!/usr/bin/python3 -I
"""Local root-only preparation of an explicit isolated session workspace. No peer API."""
import argparse
import json
import os
import pathlib
import pwd
import subprocess
import sys
import uuid
import stat


def prepare(session,rootfs):
    if os.geteuid()!=0:raise RuntimeError('Privileged workspace preparation required')
    if str(uuid.UUID(session))!=session:raise ValueError('Canonical session required')
    rootfs=pathlib.Path(rootfs)
    if not str(rootfs).startswith('/srv/forge-remote/') or rootfs!=rootfs.resolve() or not rootfs.is_dir():
        raise ValueError('Rootfs must be an explicitly prepared directory below /srv/forge-remote')
    for path in (rootfs,*rootfs.parents):
        info=path.lstat()
        if info.st_uid!=0 or info.st_mode & 0o022:raise ValueError('Rootfs and ancestors must be root-owned and protected')
    # Required mount targets and tools are supplied by the trusted operator rootfs.
    for target in ('workspace','proc','dev','tmp','run'):
        path=rootfs/target
        if not path.is_dir() or path.is_symlink():raise ValueError('Prepared rootfs mount targets required')
    for secret in ('run/docker.sock','run/forge-remote','var/lib/forge-agent','etc/forge-remote'):
        if (rootfs/secret).exists():raise ValueError('Control artifacts forbidden in workload rootfs')
    parent=pathlib.Path('/srv/forge-remote/workspaces');parent.mkdir(mode=0o700,exist_ok=True)
    if parent.is_symlink() or parent.stat().st_uid!=0 or parent.stat().st_mode & 0o077:raise ValueError('Workspace parent conflict')
    manifests=pathlib.Path('/etc/forge-remote/workspaces')
    manifest=manifests/(session+'.json')
    if manifest.exists():
        sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
        from workload_units import prepared_context
        existing=prepared_context(manifests,session)
        if existing['rootfs']!=str(rootfs):raise ValueError('Existing rootfs differs')
        return
    pending=manifests/('.'+session+'.pending')
    retry=pending.exists()
    if retry:
        info=pending.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=0 or stat.S_IMODE(info.st_mode)!=0o600 or pending.read_text()!=session:
            raise ValueError('Unsafe provisioning marker')
    else:
        fd=os.open(pending,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'w') as marker:
            marker.write(session);marker.flush();os.fsync(marker.fileno())
    user='frw-'+uuid.UUID(session).hex[:24]
    try:
        account=pwd.getpwnam(user)
        if not retry or account.pw_uid==0 or account.pw_dir!='/nonexistent' or account.pw_shell!='/usr/sbin/nologin':
            raise ValueError('Workload identity conflict')
    except KeyError:
        subprocess.run(['/usr/sbin/useradd','--system','--user-group','--no-create-home','--home-dir','/nonexistent','--shell','/usr/sbin/nologin',user],check=True,timeout=10)
    account=pwd.getpwnam(user)
    workspace=parent/session
    if workspace.exists() or workspace.is_symlink():
        info=workspace.lstat()
        if not retry or not stat.S_ISDIR(info.st_mode) or info.st_uid!=account.pw_uid or info.st_gid!=account.pw_gid or stat.S_IMODE(info.st_mode)!=0o700:
            raise ValueError('Existing workspace conflict')
    else:
        workspace.mkdir(mode=0o700)
        os.chown(workspace,account.pw_uid,account.pw_gid)
    fd=os.open(manifest,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as output:
        json.dump({'rootfs':str(rootfs),'workspace':str(workspace),'user':user},output);output.flush();os.fsync(output.fileno())
    pending.unlink()
    print('PREPARED: explicit isolated session workspace; no access granted')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session',required=True);parser.add_argument('--rootfs',required=True)
    args=parser.parse_args()
    try:prepare(args.session,args.rootfs)
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError):
        print('NOT READY: workspace preparation failed; preserve owned artifacts for inspection',file=sys.stderr);sys.exit(1)
