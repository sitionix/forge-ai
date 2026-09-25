# MCP Stage 3 — execution-scoped gateway evidence (2026-09-25)

Scope: [roadmap.md](roadmap.md) Stage 3 and the approved [design](stage-3-design.md). Stage 4 Codex configuration, Stage 5 Settings UI and OAuth are separate. The tests use local synthetic credentials and an ephemeral MCP HTTP server; no live provider or production credential was used.

## Implementation map

| Boundary | Files and responsibility |
|---|---|
| Domain | `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/McpRuntimeGrant.java`, `McpRuntimeGrantHandle.java`; `port/McpRuntimeGrantRepository.java`, `McpRuntimeToolView.java`, `McpGatewayRuntime.java`, `McpRemoteToolClient.java`: grant identity, one-time bearer handle, ports, and pre-dispatch admission callback. No SDK or HTTP types enter a persisted execution snapshot. |
| Application | `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/mcp/McpGatewayService.java`, `McpGatewayAccessException.java`: trusted issue, live lease/workflow/project/connection checks, exact tool approval, temporary decrypt and zeroing, pre-dispatch admission, revocation and safe telemetry. `McpConnectionService.java` and `McpProbeService.java` revoke changed grants. |
| Grant and tool view infrastructure | `services/forge-agent/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/mcp/gateway/InMemoryMcpRuntimeGrantRepository.java`, `SdkMcpGatewayToolView.java`: bounded hashed-token store with monotonic deadline and atomic admit/revoke ordering; transient per-grant approved full SDK schemas without retained external credential. |
| External protocol | `services/forge-agent/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/mcp/protocol/SdkMcpRemoteClient.java`: existing SDK 0.18.4 client; current-schema recheck and admission immediately before one `tools/call`, bounded shared deadline, full SDK result serialization preserving `content.type`. No automatic write-call retry. |
| Agent HTTP/security | `services/forge-agent/boot/src/main/java/com/sitionix/forgeagent/mcp/AgentMcpGatewayConfiguration.java`, `McpGatewayController.java`, `McpGatewayRuntimeFilter.java`, `McpGatewayProtocolAdapter.java`; `api-rest/.../security/AgentManagementRoutePolicy.java`, `AgentManagementAuthenticationFilter.java`: exact runtime route, distinct bearer, Host/Origin/body checks, SDK stateless handler with safe error output, management guard ownership for other routes. Boot `application.yml` adds bounded grant/body configuration. |
| Execution cleanup | `application/.../runtime/AgentSessionLeaseService.java`, `WorkflowExecutionCoordinator.java`, `AgentExecutionRecoveryService.java`: terminal finish, cancellation and expired recovery revoke execution grants. |

## Verified local behavior

- **PASS — grant/store tests:** 32-byte random bearer returned once; only SHA-256 hash held in the bounded store; wrong connection, expiry, restart, revocation, capacity, overlapping turns and post-revoke admission fail closed. A latch-controlled test orders an in-flight admission and revoke without sleeps. Admission and revocation share the store monitor; a call admitted before revoke can finish, while later calls fail.
- **PASS — application tests:** issuer derives project from the live session/workflow, checks current lease and selected-project access, validates encrypted credential identity and exact approved schema, decrypts only around discovery/call and zeroes temporary bytes. Changed approval/credential and disabled connection deny before upstream. Capacity denial performs no discovery/decrypt. Management Test with unchanged tools preserves grants; changes revoke them. Terminal, cancel and recovery paths have focused regression tests.
- **PASS — real HTTP/SDK synthetic fixture:** a standard MCP SDK client initializes the Agent endpoint, lists only the approved tool, calls it through the real `McpGatewayService` and `SdkMcpRemoteClient`, and receives a typed text result. The upstream `tools/call` counter is **1** after the allowed call. Wrong runtime bearer, guessed tool and wrong Origin leave it at **1**. Revocation while upstream `tools/list` is blocked causes the resumed request to fail before `tools/call`; the counter remains **1**. The old bearer then receives HTTP 401 with the counter still **1**. A new grant's blocked upstream write call times out safely; its counter is **2** and remains **2** after release, proving no replay. The fixture first exposed missing MCP `content.type` in the serialized Stage 2 result; serializing the complete SDK result now preserves it and the real gateway call succeeds.
- **PASS — protocol and auth tests:** malformed/unsupported JSON-RPC, unsafe exception and synthetic-secret canaries are kept out of public errors and captured logs; only tools capability is advertised. Agent ForgeIT covers management/runtime guard separation, missing/wrong operator/service/runtime credentials and alternate path attempts. Disabled MCP does not require gateway beans.

## Verification

| Check | Actual result |
|---|---|
| Focused gateway/store/SDK tests | **PASS**, 44 tests, 0 failures/errors/skips; `/tmp/forge-stage3-focused-final2.log`. |
| Full Agent verify | **PASS**, exit 0, 1389 tests, 0 failures/errors, 8 skips; `/tmp/forge-stage3-agent-verify-final2.log`. The 8 opt-in skips are **NOT_VERIFIED**, not PASS. |
| Full Nexus verify | **PASS**, exit 0, 378 tests, 0 failures/errors/skips; `/tmp/forge-stage3-nexus-verify.log`. Nexus production code was unchanged by this Stage 3 branch. |
| `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/infrastructure/local,services/forge-agent/boot -am dependency:analyze` | **PASS**, exit 0; `/tmp/forge-stage3-dependency-analyze-final.log`. All new SDK/Reactor/Jackson/SpringWeb/Servlet dependencies are used. Existing used-undeclared/unused warnings remain across the reactor, including transitive Spring/JUnit warnings in local and Boot; no new Stage 3 dependency is reported unused. |
| `git diff --check` | **PASS** after the final edits. |
| Fresh PR CI | **NOT_VERIFIED** — no PR CI result yet. |

Historical Stage 0/2 OS probes were not rerun. **NOT_VERIFIED:** live external MCP provider, DNS rebinding resistance of the accepted SDK transport, deployment TLS/private CA and runtime sandbox networking, Codex fresh/resume injection and production execution wiring (Stage 4), and browser UI/OAuth. The local fixture verifies the Agent gateway boundary, not those deployment paths.
