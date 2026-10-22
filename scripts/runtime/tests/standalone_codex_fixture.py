#!/usr/bin/python3 -I
"""Opt-in disposable managed Codex probe; never installs/changes the production launcher.
Run as root via the operator's authorized sudo helper. Uses the real launcher code,
installed runtime UID/binary/auth, and one UUID-named workspace. No production DB writes.
"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys

source=Path(__file__).resolve().parents[1]/'forge-runtime-launcher.py'
spec=importlib.util.spec_from_file_location('launcher',source)
launcher=importlib.util.module_from_spec(spec);spec.loader.exec_module(launcher)
config=launcher.load_config();launcher.caller(config)
# Narrow this isolated probe to its project resources. Never broaden the installed profile.
config=dict(config,global_resources_enabled=False)
identity=launcher.identifier(sys.argv[2]);project=Path(config['workspace_roots'][0])/('.standalone-proof-'+identity)
mode=sys.argv[1]
if mode=='setup':
    project.mkdir(mode=0o770)
    skill=project/'.agents/skills/forge-proof';skill.mkdir(parents=True)
    (skill/'SKILL.md').write_text('---\nname: forge-proof\ndescription: Use when asked for the Forge proof marker\n---\nRun `.agents/skills/forge-proof/scripts/proof.sh` directly from the workspace. Include its output.\n')
    (skill/'scripts').mkdir()
    (skill/'scripts/proof.sh').write_text('#!/bin/sh\nprintf MANAGED-SKILL-7341\n')
    agents=project/'.codex/agents';agents.mkdir(parents=True)
    (agents/'forge-proof-child.toml').write_text("name='forge-proof-child'\ndescription='Harmless managed proof child'\ndeveloper_instructions='Reply only MANAGED-CHILD-8290.'\n")
    for parent,dirs,files in os.walk(project):
        os.chown(parent,config['control_uid'],config['runtime_gid']);os.chmod(parent,0o2770)
        for name in files:os.chown(Path(parent)/name,config['control_uid'],config['runtime_gid']);os.chmod(Path(parent)/name,0o660)
    os.chmod(skill/'scripts/proof.sh',0o750)
    print(project)
elif mode=='launch':
    execution=launcher.identifier(sys.argv[3]);launcher.workspace(config,str(project))
    reader=os.fdopen(os.dup(sys.stdin.fileno()),'rb',buffering=0)
    try:manifest=launcher.read_startup_envelope(reader,lambda v:v,2097152)
    finally:reader.close()
    sys.exit(launcher.start(config,execution,str(project),[config['codex_binary'],'app-server','--stdio'],resource_snapshot=manifest))
elif mode=='stop':launcher.stop(config,launcher.identifier(sys.argv[3]))
elif mode=='cleanup':
    # UUID-derived fixed child only, test-owned content. Caller must stop its executions first.
    if project.parent!=Path(config['workspace_roots'][0]) or project.is_symlink():raise ValueError('unsafe fixture')
    shutil.rmtree(project)
else:raise ValueError('unsupported fixture command')
