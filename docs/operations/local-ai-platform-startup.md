# Local AI platform operations

Use the repository-root runtime contract on both Ubuntu and macOS:

```bash
just start
just status
just logs knowledge
just logs jarvis
just stop
```

`just start` owns the complete Forge stack: Docker-managed Postgres plus
Knowledge, Jarvis, Agent, and Nexus. On Ubuntu the processes and logs are owned
by systemd. On macOS they are owned by the detached managed session.

To follow all application logs, run `just logs`. To access an interactive
managed-session window on macOS, run `just attach knowledge` (or `jarvis`,
`agent`, or `nexus`). Ubuntu systemd workloads are non-interactive; use their
logs instead.

Startup is successful only after all four health endpoints respond. Check real
process, health, and Postgres state with `just status`. Restart the same selected
backend with `just restart`.
