# Stage 8 — local Codex helper on the ACCESSOR

The local `forge-remote exec` helper asks the local Forge Agent to run one command
through an ACTIVE ACCESSOR session. The helper is a small Python client of a
protected Unix socket. Agent keeps the persisted private key and pinned SSH host
identity, then uses the existing Stage 5 managed SSH transport. The helper never
reads a pairing token, session private key, Forge DB credential or browser secret.
It never runs the requested command on the local machine.

The local Agent must run as `forge-control`; the local Codex process runs as a
different, explicitly selected unprivileged user. After the existing Stage 2–6
ACCESSOR installation and management setup, prepare this additional boundary:

```sh
sudo python3 scripts/remote-access/prepare_local_exec.py --operator-user <local-codex-user>
```

The setup prepares `/run/forge-remote/local-exec/` as
`forge-control:<operator-primary-group>` mode `2750` (setgid), a root-owned tmpfiles declaration,
and `/etc/forge-remote/management/local-exec-agent.env` as `forge-control` mode
`0600`. It does not start a service, grant SSH access or modify an existing unit.
The tmpfiles declaration uses the operator's actual primary group resolved from
its GID; it does not assume the group has the same name as the operator. An
unresolvable GID stops setup before it writes Stage 8 artifacts.
Attach that env file only to the dedicated `forge-control` Agent unit, after its
existing Agent env file, then restart that Agent using the ordinary deployment
procedure. The optional listener is disabled by default. Its startup fails if the
operator username is absent or the socket directory is not protected.

After reviewing the packaged artifact, install it on the ACCESSOR's PATH:

```sh
sudo install -o root -g root -m 0755 scripts/remote-access/forge-remote /usr/local/bin/forge-remote
```

The script uses only Python's
standard library. Check that the installed socket is owned by `forge-control`
with mode `0660`; the helper also checks its protected parent. The Agent verifies
the peer OS user via `SO_PEERCRED`. Only the configured local operator can submit
an execution request. A GRANTOR workload has no mount or authority path to this
ACCESSOR-local socket.

Example:

```sh
forge-remote exec --session <session-id> --cwd /workspace/repository -- /usr/bin/git status --short
forge-remote exec --session <session-id> --cwd /workspace/repository -- /usr/bin/python3 -m pytest
```

The command must start with an absolute executable path. Every argument after
`--` is sent literally; no local shell evaluates it. `--timeout` defaults to one
hour and is bounded by the existing command model to 24 hours. stdin, stdout,
stderr and the remote exit code pass through the local socket. A local SIGINT
closes that socket; Agent calls `RemoteAccessCommandExecution.close()`, which
cancels the managed SSH execution and remote workload. A refused or unavailable
session returns exit `125`; SIGINT returns `130`. There is no local execution
fallback. A failed remote command returns its actual exit code.

The helper reads available stdin bytes immediately, including a short write from
an open pipe. Agent sends a small local keepalive frame while the command runs.
This lets Agent detect local disconnect and cancel even when writing to the
remote command's stdin is blocked because that command does not read it.

Codex's built-in local file tools remain local. For remote edits, run commands
through this helper (for example a remote editor/script or `git apply` through
stdin), then run remote tests through the same session. Prompt instructions are
not a substitute for the Agent socket's OS boundary. The helper does not expose
Codex history or account credentials to the GRANTOR. No remote app-server API,
new workflow NodeType or automatic workflow executor routing is part of Stage 8.

The Stage 8 privileged fixture extends the existing isolated Stage 5 real
SSH/PostgreSQL/systemd fixture. It runs this helper as a separate local operator,
checks a literal argument and remote file read/edit/test, then interrupts a
long-running helper invocation and verifies the managed unit, main process,
setsid descendant, registry and fence are gone. It also checks refusal after
revoke. This is not a live Codex-on-two-machines acceptance; that belongs to
Stage 9.
