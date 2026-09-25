# Stage 3 — execution-scoped MCP gateway design

Status: proposed. Scope is Stage 3 of [roadmap.md](roadmap.md); Stage 4 Codex configuration and Stage 5 UI are separate.

## Outcome and boundaries

An active Agent execution can receive one opaque runtime grant per approved MCP connection. A standard MCP client uses that grant at an Agent-internal HTTP endpoint for `initialize`, `tools/list`, and `tools/call`. The client never receives the external credential. Each request is checked against the live execution and connection policy before upstream I/O. No management route can issue a grant.

The existing `McpRemoteToolClient` remains the upstream invocation port. The new application boundary owns grant issuance, policy checks, credential decryption, and revocation. Infrastructure owns grant storage and the MCP SDK protocol representation. No SDK types, protocol JSON, or bearer secrets enter persisted execution snapshots or the Nexus API.

## Grant identity and lifetime

The trusted execution path supplies an `AgentSessionExecutionClaim` and its execution deadline. The issuer resolves its session, node run, workflow run and project from repositories; callers cannot provide a project ID as authority. It verifies the current lease and active node run before issuing a grant. A cryptographically random token is returned once; only its hash is held in bounded Agent memory. It binds installation, execution turn, connection, project, endpoint/auth identity, approved tool fingerprints and deadline. Expiry cannot exceed the supplied execution deadline. A new invocation or recovery receives a new token; restart loses all grants.

The gateway checks the current lease/session and connection on **every** list and call. Disable, remove, endpoint/auth edit and reduced project/tool access revoke affected grants. Re-enable never revives them. Newly approved tools are not added to an existing grant. A changed upstream schema is rejected by the same-session Stage 2 check immediately before `tools/call`. An upstream write-capable call is never retried automatically.

## HTTP and MCP protocol

The Agent serves a dedicated `/internal/mcp/connections/{connectionId}` route. Only a runtime `Authorization: Bearer` grant is accepted; operator/service credentials fail. The route rejects invalid Host/Origin, bounds the request body, and uses the SDK 0.18.4 stateless server handler and JSON-RPC mapper. It exposes only `initialize`, `tools/list`, and `tools/call`; unsupported capabilities are not advertised. A forbidden request is rejected before calling the SDK upstream client.

The SDK's stock servlet transport is unsuitable for this boundary: its error paths log and sometimes return `Exception.getMessage()`, which can contain a submitted payload. A narrow Spring servlet adapter will perform HTTP validation and safe response mapping, then delegate protocol messages to the SDK server handler. It will not implement a second MCP parser, stream engine, or general HTTP framework. Protocol errors remain protocol errors; upstream tool `isError` and structured content remain tool results.

Each grant has a separate SDK server view of the tools approved at issuance. Infrastructure obtains current full tool schemas from the upstream SDK within a bounded call while credentials are temporarily decrypted, compares their fingerprints with approvals, and then discards plaintext. Stored inventory remains summaries only. The view can lose tools after current policy changes; it never gains tools during that grant. Before every `tools/call`, application policy rechecks the exact name/fingerprint and decrypts the current credential only for that upstream call, zeroing plaintext afterward.

## Failure and cleanup

Unknown, expired, revoked, wrong-connection and wrong-execution grants fail without upstream calls. Cancellation and terminal execution cleanup revoke grants. A request already sent upstream before revoke may complete; subsequent calls fail. Timeouts and connection resets have an unknown write outcome and are not replayed. Safe telemetry records execution/connection/tool identifiers, duration and outcome only; no arguments, response body, bearer or external credential.

## Verification

Use local synthetic MCP fixtures and ForgeIT endpoint contracts. Cover grants for another execution/project/connection, expiry, restart, disable/re-enable/remove, endpoint and permission edits, current schema changes, two connections with equal tool names, overlapping invocations, malformed protocol, unsupported methods, cancellation and response timeout. Assert zero upstream calls for every local denial. Canary secrets must be absent from HTTP errors and captured logs. A real SDK client must initialize, list and call through the gateway; no live provider is required.

## Explicit limits

No Codex injection, UI, OAuth, database grant table or generic IAM framework in Stage 3. The Stage 2 DNS-rebinding and deployment TLS gaps remain `NOT_VERIFIED`; gateway work does not turn them into PASS.
