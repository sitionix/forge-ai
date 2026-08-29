# Forge runtime

Forge exposes one lifecycle contract on supported developer and operator hosts:

```bash
just start
just stop
just restart
just status
just logs [service]
just attach [service]
```

The runtime resolver selects one backend before performing an operation. Ubuntu
uses `SYSTEMD` only when systemd is the active, reachable system manager and all
Forge units are installed. macOS uses `MANAGED_LOCAL_SESSION`. A selected
backend is authoritative: a systemd error is reported and never causes a local
process fallback.

Logical service names are `knowledge`, `jarvis`, `agent`, `nexus`, and
`postgres`. `just logs` follows all Forge application logs; pass a service name
to follow only that service. `just attach <service>` attaches to a macOS managed
session window. Systemd does not expose interactive application sessions, so on
Ubuntu use `just logs <service>`.

## Ubuntu

Install or refresh the four systemd units once:

```bash
just systemd-install
```

The units are:

- `forge-knowledge.service`
- `forge-jarvis.service`
- `forge-agent.service`
- `forge-nexus.service`

Systemd owns application process identity, restart policy, exit state, and
journald output. Docker Compose owns `forge-agent-postgres`. `just start` starts
Postgres, starts the units, and waits for every application health endpoint.
Lifecycle commands fail explicitly when the manager or installation is unusable.

## macOS

Install `tmux` and the normal Forge build prerequisites. `just start` builds the
applications, starts Docker-managed Postgres, and creates a detached `tmux`
session named `forge-ai`. That session owns the four application processes and
their output. The launching terminal can exit without stopping Forge.

`just status` reads the actual pane exit state and application health. A crashed
child is reported as failed, including its exit code. `just stop` removes only
the explicitly owned Forge session and stops its Postgres dependency; it never
kills processes by port.

## Runtime target discovery

On Ubuntu the installed units remain ordinary systemd targets and continue to
work with the existing `LOCAL + SYSTEMD` discovery and inspection providers.
