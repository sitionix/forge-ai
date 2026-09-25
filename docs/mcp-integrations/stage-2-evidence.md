# MCP Stage 2 — Custom MCP Test та inventory (2026-09-25)

Scope: backend частина Flow 2 з [flows.md](flows.md) після merged Flow 1. Stage 1 connection/credential storage перевикористано. Це **не** завершення всього Stage 2 checklist із [roadmap.md](roadmap.md): controlled tool invocation і гарантія DNS rebinding лишаються окремими пунктами.

## Карта реалізації

| Межа | Файли та роль |
|---|---|
| Agent domain | `domain/.../model/McpProbeReport.java`, `McpToolSummary.java`, `exception/McpProbeException.java`, `port/McpRemoteProbe.java`, `McpToolInventoryRepository.java`: typed summaries, safe failure reasons, protocol/persistence ports. |
| Agent application | `application/.../mcp/McpProbeService.java`: owner-scoped read, decrypt for one call, plaintext zeroing, publish inventory only after successful probe. `McpConnectionService.java`: credential replacement/removal clears stale approvals and check state. |
| Protocol boundary | `infrastructure/local/.../mcp/protocol/SdkMcpRemoteProbe.java`, `McpProbeHttpClientBuilder.java`, `McpProbeConfiguration.java`: SDK 0.18.4 initialize, explicit bounded `tools/list` pages, canonical SHA-256 schema fingerprints, fixed HTTP status observation, redirects disabled, initial resolved-address policy, SSL bundle. `infrastructure/local/pom.xml` directly declares `mcp-core` and `mcp-json-jackson2`. |
| Persistence | Existing `infrastructure/postgres/.../V39__add_mcp_tool_inventory.sql` supplies `mcp_discovered_tools`; `V42__expand_mcp_discovered_tool_metadata.sql` allows full optional protocol descriptions. `PostgresMcpToolInventoryRepository.java`: inventory and exact fingerprint approvals under connection lock. `PostgresMcpConnectionRepository.java`: identity/check reset removes stale inventory. Completed probe cannot publish an inventory from a replaced encrypted credential. |
| Agent API/wiring | `api-rest/.../mcp/McpProbeController.java`, `McpProbeResponse.java`, `McpConnectionsExceptionHandler.java`; `boot/.../AgentMcpProtectedConfiguration.java`, `application.yml`: protected Test, inventory, approval routes and safe status/code messages. |
| Nexus typed path | `domain/.../mcp/McpProbeReport.java`, `ForgeAgentMcpClient.java`, `ManageAgentMcpConnections.java`; `application/.../AgentMcpConnectionsUseCase.java`; `clients/agent-client/.../ForgeAgentHttpClient.java`, `ForgeAgentMcpClientAdapter.java`, `McpClientMapper.java`, `dto/McpProbeInboundResponse.java`; `api-rest/.../ForgeAiMcpConnectionsController.java`, `McpProbeResponse.java`: current operator boundary → use case → typed client → Agent, adapter execute → mapper. |

Management routes on both services: `POST /connections/{id}/test`, `GET /connections/{id}/tools`, `PUT /connections/{id}/allowed-tools` under the existing MCP prefix. Test does not enable a connection or approve a tool. Inventory contains name, description and schema fingerprint; secrets, SDK types and raw protocol JSON do not enter Nexus responses. The existing Agent service bearer and Nexus operator session/Origin/CSRF guards own these paths.

## Verified capabilities

- **PASS, local synthetic SDK fixture:** no-auth, bearer and secret headers; initialization and two `tools/list` pages; empty inventory; JSON and bounded event-stream responses; schema fingerprint change; duplicate names rejected. Probe fixture rejects unexpected tool calls, so Test did not call a write tool.
- **PASS, local negative fixtures:** HTTP 401 → `MCP_AUTH_REQUIRED`, 403 → `MCP_FORBIDDEN`; unsupported protocol, malformed JSON and oversized body are not treated as readiness; redirect target receives zero calls; loopback/IPv6/link-local/userinfo/query/non-HTTP endpoints are denied unless an exact private host:port exception applies (link-local remains denied). SDK 0.18.4's HTTP response cap is configured. The narrow JDK client seam observes status/response metadata only; it does not parse protocol streams or expose raw body.
- **PASS, Agent ForgeIT:** V39 migration, inventory round-trip, changed schema revokes only changed approval, stale encrypted credential cannot publish an old probe result, protected management route rejects missing/wrong bearer before application, malformed approval rejected before service, synthetic credential/exception canaries absent from public response and captured logs.
- **PASS, Nexus ForgeIT:** typed Test/inventory/approval forwarding with service bearer; absent operator session, wrong Origin, missing CSRF and invalid approval rejected before Agent adapter; existing combined MCP/Remote Access auth tests remain in full verify.
- **PASS, configuration:** defaults `connect=2s`, `request=3s`, `maxPages=5`, `maxTools=500`, `maxResponseBytes=1048576`; startup rejects `requestTimeout × (maxPages + 2) > 25s` to stay below the ordinary 30s Nexus Agent read timeout. Spring Boot SSL bundle is optional and TLS verification is not disabled.

## Verification

| Command | Actual result |
|---|---|
| `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify` | **PASS** after the HTTP-status race fix, exit 0, 1343 tests, 0 failures/errors, 8 skips. Log: `/tmp/forge-mcp-agent-auth-status-final-verify.log`. |
| `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am verify` | **PASS** after merging current `main`, exit 0, 378 tests, 0 failures/errors/skips. Log: `/tmp/forge-mcp-nexus-merge-final-verify.log`. |
| Focused `McpConnectionPersistenceIT`, `AgentMcpManagementGuardIT`, `NexusOperatorSessionIT`, `SdkMcpRemoteProbeTest`, `McpProbeConfigurationTest`, mapper/adapter/service tests | **PASS**, exit 0 in their final focused runs. |
| `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/infrastructure/local -am dependency:analyze` | **PASS** command; no unused new MCP dependencies. Existing transitive Spring/JUnit warnings remain. |
| `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/clients/agent-client -am dependency:analyze` | **PASS** command; no new Nexus dependency. Existing transitive Spring/JUnit warnings remain. |
| `git diff --check` | **PASS**. |

Eight Agent skips are opt-in checks, **NOT_VERIFIED**, not PASS. An earlier Agent full verify failed on a misplaced ForgeIT request fixture; that path was corrected before the original successful full run. During the merge with newer `main`, its V39 migration introduced a strict schema-fingerprint constraint. One intermediate full run failed on old synthetic short fingerprints; the fixture was changed to valid SHA-256 values, then the focused persistence test and both full commands above passed. No personal Maven/Codex config or production secret was changed. Tests used disposable PostgreSQL/WireMock/local HTTP fixtures and synthetic credentials.

The first CI run for PR #154 exposed a race in `McpProbeHttpClientBuilder`: dispatching another POST cleared an observed 403 before probe classification. `McpProbeHttpClientBuilderTest` deterministically failed on the old code (`expected 403, was 0`); removing that per-request reset made the focused test and full Agent verify pass. The same CI run also had a Forge Knowledge failure in `test_server_request_scope_and_unknown_fail_closed`; this branch has no Forge Knowledge code diff and the prior `main` CI passed, so the next CI run must verify whether that failure recurs.

## Remaining Stage 2 / runtime gaps

- **NOT_VERIFIED / not implemented in this slice:** internal controlled tool call with `isError` and structured result semantics. No gateway, Codex injection, OAuth, Settings UI or catalog-to-connection flow was added.
- **NOT_VERIFIED:** DNS rebinding resistance. The accepted SDK transport has no pinned DNS; this code validates all initially resolved addresses, forbids redirects and proxies, and allows private destinations only by exact host:port, but a DNS change between validation and connection remains possible. Do not claim the roadmap's strict rebinding requirement as PASS.
- **NOT_VERIFIED:** live provider, real self-hosted CA/TLS fixture, long-lived event-stream timeout, deployment/privileged sandbox boundary and fresh PR CI. The SSL bundle property is wired; its deployment trust chain has not been exercised.
- **NOT_VERIFIED:** a tool server with more than five pages or 500 tools is supported end-to-end. Such input fails explicitly at the configured bound; it is not returned as partial inventory.

Next Stage 2 work before declaring the entire stage complete: add the internal typed invocation boundary and deterministic tests for MCP protocol error versus tool `isError`/structured result; resolve or explicitly accept the DNS-rebinding tradeoff in final security review; exercise SSL bundle and timeout with disposable TLS/slow fixtures. Stage 3 gateway and Stage 5 UI remain separate.
