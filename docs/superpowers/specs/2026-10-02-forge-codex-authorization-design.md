# Forge-owned Codex authorization

## Intent and scope

Add a Codex provider card to Settings → Integrations, alongside MCP. One
ChatGPT account authorizes this local Forge installation and all its Codex
consumers. Authorization comes exclusively from the Forge UI, not the owner's
terminal Codex login, personal configuration, or inherited API keys.

Implement on `feature/SITIONIX-156`. Preserve the existing uncommitted Just
Start changes and startup repair. Do not commit or push without a separate
instruction. Do not redesign Just Start or alter GitHub MCP permissions.

This design targets local Forge, with the browser on the runtime machine.
Hosted/commercial deployment and authorization across machines are out of scope.

## Findings from the current implementation

- Agent execution, MCP execution, model discovery, and recovery inspection use
  `DefaultCodexAppServerProcessStarter` and `RuntimeProcessLauncher`.
- The launcher clears inherited environment and sets a dedicated runtime HOME
  and CODEX_HOME. MCP launches also mount installation-owned configuration.
- Knowledge's `CodexAppServerClient._default_process_factory` starts the configured
  command directly, inheriting the service environment. Generation, discovery,
  and usage reporting share that client. This is a separate authorization path.
- Agent's runtime `READY` currently denotes initialization/model discovery, not
  authenticated access to inference.
- The isolated runtime returned `account=null`, `requiresOpenaiAuth=true`.
  A tool-free diagnostic reproduced HTTP 401, missing bearer/basic authentication.
- Knowledge runs with `NoNewPrivileges=true`; adding a sudo subprocess there
  would not provide a valid managed-runtime integration.

## Selected approach

Use Codex app-server's managed ChatGPT browser login through Forge Agent, the
existing owner of the isolated Codex launcher. Do not implement OAuth exchanges,
copy personal credentials, or expose a general-purpose JSON-RPC proxy.

A CLI-login subprocess was considered but rejected: it duplicates the existing
app-server transport and requires parsing CLI output. Device-code login is a
future alternative, not part of the first implementation.

## Authorization lifecycle

An Agent-owned provider authorization service maintains a single pending login
and a generation number for the installation's authorization state.

1. Read state using `account/read` from an isolated auth-management app-server.
2. Start login using the version-supported `account/login/start`, type `chatgpt`.
3. Return only a login ID and validated authorization URL to the operator UI.
   The Codex process owns its loopback callback and stays alive until completion,
   cancellation, or a bounded expiration.
4. Process `account/login/completed` and `account/updated`; verify account state
   before publishing Connected. Never treat opening the browser as success.
5. Persist credentials in the dedicated runtime profile, with restrictive file
   permissions. Codex owns token refresh. Forge stores no tokens in browser
   storage, API responses, application logs, or plaintext database columns.
6. On cancellation/timeout, cancel that login and stop its owned process. A
   cancelled or superseded completion cannot activate authorization.
7. On logout, immediately block new inference, cancel active Codex work through
   normal lifecycle callbacks, terminate owned provider processes, perform
   `account/logout`, and verify signed-out state. If cleanup/logout fails, stay
   blocked and report an error; do not claim logout succeeded.
8. A service restart reads the dedicated account state again. Pending login does
   not survive restart. Cached account state must not authorize new work alone.

Auth-management processes use a neutral installation-owned workspace, no MCP
grants, and the same isolated configuration and identity as execution processes.
Only operator authorization operations may change account state.

## One authority for all consumers

- Apply installation-owned config isolation to MCP and non-MCP launches alike.
  Personal HOME, keyrings, inherited provider keys, and project auth overrides
  must not become alternative credential sources.
- Gate every new inference turn, including fresh, durable, resumed, and retry
  paths, on the Forge account state and current authorization generation.
- Model discovery may operate while signed out, but returns explicit auth state;
  a local catalog or initialized process is not proof of inference access.
- Recovery inspection may inspect persisted state without inference; it cannot
  resume work under absent or superseded authorization.
- Knowledge delegates Codex generation, model discovery, and usage operations
  to Agent through a narrow internal service API. It no longer starts a direct
  Codex process. Preserve its existing generative/discovery/usage interfaces,
  structured output validation, cancellation, deadlines, and usage semantics.
- The internal API accepts only typed generation/discovery/usage requests,
  authenticates the Knowledge service using protected installation credentials,
  and is not published through Nexus's browser routes. No arbitrary commands,
  JSON-RPC methods, provider credentials, or MCP tool access are exposed.
- Audit Jarvis and other production callers; any indirect Knowledge consumer
  follows this same authority. No terminal-Codex fallback remains.

## Public API and UI

Add typed provider-auth operations under `/api/v1/integrations/llm` in Agent,
proxied through Nexus's normal infrastructure route:

- GET providers: Codex identity, availability, authorization state, safe account
  display information; no credentials.
- POST Codex login: begin one attempt or return the existing pending attempt.
- GET login by ID: pending, completed, failed, cancelled, or expired.
- DELETE login by ID: cancel that attempt only.
- POST Codex logout: invalidate execution access and sign out.

Follow existing operator security patterns: same-origin checks for mutations,
browser-bound attempt ownership, no-store responses, bounded polling, safe
errors, validated provider URLs, and no cross-origin login/logout triggers.

Show one Codex card with a bundled icon, Sign in with ChatGPT, pending/error
states, safe account information after login, and Sign out. Popup blocking has
an explicit retry/open-link action. Dispose polling/listeners when leaving the
page. Preserve existing MCP UI and connection behavior.

Use an explicit `CODEX_AUTH_REQUIRED` error for unauthenticated inference rather
than the generic MCP execution failure. Keep auth availability distinct from
provider process availability and inference verification.

## Verification and acceptance

- Terminal Codex signed in, Forge signed out: every Forge inference path refuses
  to run, regardless of inherited keys or personal provider configuration.
- Forge browser login succeeds: account/read confirms ChatGPT authorization in
  the isolated runtime; fresh Agent, durable/resumed Agent, Knowledge generation,
  discovery, and usage use that authorization only.
- Test concurrent starts, duplicate login clicks, browser denial, timeout,
  cancellation, stale completion, popup blocking, and service restart.
- Test logout during active work, cleanup failure, and reconnection. Old provider
  processes cannot retain access or restart inference after sign-out.
- Regression tests prove no tokens leak into UI/API/logs and internal service
  calls reject unauthenticated or arbitrary-method requests.
- Existing Agent context modes, cancellation/recovery, Knowledge outputs, and
  MCP permission enforcement continue to pass their regression tests.
- Run a real normal Agent workflow: GitHub MCP creates/reads the disposable PR,
  then closes/reads it. Verify actual tool-call/runtime evidence, not text claims.
  Clean disposable artifacts after successful verification as originally scoped.
- Use normal `just stop`, `just start`, and `just status` for runtime verification.
  Report any live blocker without unrelated fixes.

## Official reference

[Codex app-server authentication](https://learn.chatgpt.com/docs/app-server#auth-endpoints)
documents the managed browser login, account state, cancellation, and logout
protocol. Validate request/notification schemas against installed Codex 0.160.0
before implementation; do not assume newer optional fields exist.
