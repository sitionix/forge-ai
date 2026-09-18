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
by systemd. On macOS they are owned by launchd LaunchAgents generated for the
current checkout.

To follow all application logs, run `just logs`; pass `knowledge`, `jarvis`,
`agent`, `nexus`, or `postgres` to follow one logical service.

Startup is successful only after all four health endpoints respond. Check real
process, health, and Postgres state with `just status`. Restart the same selected
backend with `just restart`.

For SSH Git remotes on Ubuntu, run `just systemd-install` from a terminal where
`git ls-remote <repository-url>` succeeds, then run `just restart`. The installer
captures that terminal's `SSH_AUTH_SOCK` in the systemd environment so Forge can
use the same SSH agent. Run the installer as your regular service user; it invokes
sudo for the installation steps itself.

The SSH agent must remain available with the required key loaded. If its socket
path changes (for example, after a new login), repeat `just systemd-install` and
`just restart` from the working terminal. Installation without `SSH_AUTH_SOCK`
still works, but does not configure an SSH agent for the services.
