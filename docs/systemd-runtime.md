# Forge AI systemd Runtime

Forge AI can be installed as four systemd-owned workloads:

- `forge-agent.service`
- `forge-nexus.service`
- `forge-knowledge.service`
- `forge-jarvis.service`

These units are runtime wrappers around the existing deployable applications. They do not create a new Forge runtime provider or merge the applications into one process.

## Workflow

```bash
just systemd-install
just start
just status
just restart
just stop
```

`just start`, `just stop`, `just status`, and `just restart` are the public installed-runtime commands. They manage the four Forge systemd units, where systemd owns process lifecycle, `MainPID`, restart policy, startup timestamp, exit status, and journald output. The lightweight PID-file launcher remains internal for development-focused checks and cannot compete with active systemd workloads.

The units use `Restart=on-failure` with `RestartSec=5s`. Normal operator stops are not restarted; failed processes are restarted by systemd and remain visible through systemd state and journal metadata.

`forge-agent-postgres` remains Docker-managed by `compose.yaml`. It is started by `just start` as a prerequisite for `forge-agent.service`, but it is not modeled as a Forge systemd Service.

## Discovery

After the units are installed and started on a Linux host with systemd, the existing runtime target discovery flow sees them through `LOCAL + SYSTEMD` because they are ordinary systemd unit names:

```bash
systemctl list-units --type=service --all --no-legend --plain
```

Runtime inspection continues to use the existing SYSTEMD provider:

```bash
systemctl show --no-pager --property=ActiveState,SubState,ExecMainStartTimestamp,MainPID,ExecMainStatus,Result -- forge-agent.service
```
