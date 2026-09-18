# Forge runtime

Forge exposes one lifecycle contract on supported developer and operator hosts:

```bash
just start
just stop
just status
just logs [service]
```

The runtime resolver selects one backend before performing an operation. Ubuntu
uses `SYSTEMD` only when systemd is the active, reachable system manager.
`just start` installs or refreshes the Forge units automatically. macOS uses `LAUNCHD`. A selected
backend is authoritative: a systemd error is reported and never causes a local
process fallback.

Logical service names are `knowledge`, `jarvis`, `agent`, `nexus`, and
`postgres`. `just logs` follows all Forge application logs; pass a service name
to follow only that service.

## Ubuntu

Start or restart Forge with one command:

```bash
just start
```

The units are:

- `forge-knowledge.service`
- `forge-jarvis.service`
- `forge-agent.service`
- `forge-nexus.service`

Systemd owns application process identity, restart policy, exit state, and
journald output. Docker Compose owns `forge-agent-postgres`. `just start` builds
the applications, installs or refreshes the units and environment, reloads systemd,
starts Postgres, restarts the units, and waits for every application health endpoint.
Installation uses sudo when needed. Run `just start` as the regular service user.
The current terminal's `SSH_AUTH_SOCK`, when set, is passed to the services for
SSH Git access. Repeating `just start` refreshes it if the socket path changes.
Lifecycle commands fail explicitly when the manager or installation is unusable.

## macOS

`just start` builds the applications, starts Docker-managed Postgres, renders
LaunchAgent plists for the current checkout under `~/Library/LaunchAgents`, and
loads Knowledge, Jarvis, Agent, and Nexus into the current user's launchd
domain. Repeating `just start` unloads and loads the owned services again so
updated plist settings take effect. The command returns while launchd continues
to own the services.

Application stdout and stderr are owned by launchd under
`var/launchd/logs`. `just logs [service]` follows those files, `just status`
combines launchd state with application health, and `just stop` unloads the four
Forge LaunchAgents before stopping Postgres.

## Runtime target discovery

On Ubuntu the installed units remain ordinary systemd targets and continue to
work with the existing `LOCAL + SYSTEMD` discovery and inspection providers.
