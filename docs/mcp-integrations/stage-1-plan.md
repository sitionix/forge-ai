# MCP Integrations Stage 1 — implementation plan

Current delivery authorization: user subsequently requested a PR. Root may commit/push the completed Stage1 changes on `feature/SITIONIX-142` and create the PR against main. No merge, deployment or Stage2 implementation. Earlier no-PR wording below records the original execution constraint; it no longer blocks this explicitly requested delivery.

> Execute task-by-task using superpowers:subagent-driven-development; tests first, independent task and final review. User authorized work on main in isolated worktree. No push, PR, comments, merge or production deployment.

Goal: global installation-owned MCP connections with encrypted credentials, explicit project/tool policy, typed Agent/Nexus management, and a proven control/runtime boundary before endpoints are enabled.
Architecture: reuse ForgeInstanceIdentityRepository, existing Agent domain/application/PostgreSQL/local modules and Nexus typed proxy. No MCP network calls/runtime injection/UI/OAuth/catalog in Stage 1.
Tech stack: Java 21, Boot 3.3.4, PostgreSQL/Flyway, Java JCE AES-GCM, existing ForgeIT/JUnit. No platform upgrade.
Spec: roadmap.md Stage 1 and sections 3–6; stage-0-evidence.md prerequisites. Base edf49dbf origin/main, worktree /tmp/forge-mcp-stage1, branch main by explicit instruction.

## Global constraints

- Preserve original checkout and unrelated changes. Product changes confined to this worktree.
- Installation identity now exists in main: reuse ForgeInstanceIdentityRepository; no new owner/tenant framework.
- Auth is absent even in current main. New management endpoints must be disabled by default and never expose secret material before their prerequisite is configured and verified.
- Credentials write-only, safe exceptions/toString/DTOs, KEEP/REPLACE/REMOVE; no OAuth state until Stage 6.
- All/Selected is explicit; empty Selected denies. Stage 1 has no discovered tools and never grants access to unknown tools.
- No external MCP calls, no UI, no new runner, no microservice; runtime process boundary is the required Stage 0 prerequisite, not MCP Stage 4 integration.
- No production users/services/config/secrets touched. Privileged checks only in disposable fixtures; deployment instructions are artifacts, not live installation.
- Existing Remote Access worktree is independent. Do not edit it or assume its unmerged Stage 5 exists in main.

## Review focus

- Missing/wrong key, ciphertext swaps, key rotation and transactional rollback must not reveal or lose credential data.
- Both direct Agent and Nexus calls require the correct caller; runtime scope never authorizes management.
- Environment cleanup alone is not separation: runtime must fail reading synthetic control files/parent proc and cannot access management authority.
- Foreign/deleted project and empty Selected never become ALL; duplicate service names never merge identities.
- Default/off configuration preserves current no-MCP workflows; enabling without prerequisites fails closed, not by trusting an arbitrary boolean.

## Task 1: Agent connection model, application, persistence and encryption

Files: new domain/model/Mcp*.java, domain/port/Mcp*.java; application/mcp/McpConnectionService.java and tests; infrastructure/postgres/adapter/PostgresMcpConnectionRepository.java; next forward migration (V38 if still free); infrastructure/local/mcp/AesGcmMcpCredentialCipher.java and tests. Add real PostgreSQL ForgeIT in boot using existing contracts/managers. No REST, auth configuration, runtime or Nexus edits in this task.

Interfaces: immutable McpConnection metadata with UUID id, installationId, displayName, URI endpoint, McpAuthType (NONE/BEARER/SECRET_HEADERS), enabled, McpProjectAccess (ALL/SELECTED + Set<UUID>), explicit Set<McpAllowedTool> (name/schema fingerprint), createdAt/updatedAt, optional checkedAt/safe diagnostic. No raw credentials/ciphertext in connection metadata. New connections have no approved tools; caller cannot authorize unknown tool schema before discovery exists. Credential secret object is a non-record redacted class, defensive copies and explicit access, no bean getters/default serialization. Ciphertext is separate opaque non-public representation, never returned by service read. Domain ports allow metadata load/list, transactional metadata+encrypted credential writes, delete, and encrypted credential access only to cipher/service. Cipher encrypt/decrypt must bind installation + connection + purpose in AAD.

Service operations: create, list/get, update, setEnabled, remove, policy read and explicit reencrypt to configured active key. KEEP/REPLACE/REMOVE is an enum plus optional secret input, validate combinations. REPLACE of mask (•••• or ****) rejected. NONE has no secret; BEARER/header missing secret remains explicitly unconfigured/unusable. Project IDs checked with existing ProjectRepository against installation domain (single installation); empty SELECTED denies. Endpoint is http/https absolute without userinfo/fragment/secret query; only syntax validation, never outbound I/O. Header names valid HTTP token and values no CR/LF; reject hop-by-hop/Host/Cookie/forwarding transport headers. Duplicate display names allowed.

Encryption: JCE AES/GCM/NoPadding, random 12-byte nonce, 128-bit tag, 256-bit keys. active key ID and explicit old key IDs, key bytes supplied through dedicated local key source (production configuration wired by prerequisite task). Ciphertext swaps across connection/installation/purpose fail. No plaintext fallback. Safe exception without provider messages/values. Tests supply synthetic key maps; no host secret/config read.

- [x] Write and run failing unit tests for validation/policy, credential updates, AES tamper/AAD swap/key ID/rotation/redaction.
- [x] Implement minimal domain/application/cipher, run tests green.
- [x] Write failing real Postgres migration/roundtrip/rollback/delete tests with existing ForgeIT patterns, then implement adapter/migration and pass tests.
- [x] Verify existing focused tests remain green. Do not invent a new test manager where existing contracts suffice.

Commands (from worktree):
```sh
mvn -B -f services/forge-agent/pom.xml -pl application,infrastructure/local,infrastructure/postgres -am test -Dtest='*Mcp*Test' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -f services/forge-agent/pom.xml -pl boot -am verify -Dit.test='*Mcp*IT' -Dfailsafe.failIfNoSpecifiedTests=false
```
Expected: new tests first fail for missing behavior, then pass; IT uses isolated ForgeIT PostgreSQL, never localhost production DB. Report exact counts/commands and any skipped tests.

## Task 2: Required control/runtime and management authentication prerequisite

Before any REST exposure. Reuse current process starter, protected local file conventions and service packaging. Define one installation operator session in Nexus with bootstrap credential file, HttpOnly SameSite cookie, bounded expiration, exact configured Origin/Host and session-bound CSRF for mutation; Agent new management routes require a distinct Nexus service credential. When enabled, guard all Agent and Nexus /api/v1 control endpoints: existing repository/runtime/Compose routes can act as deputies. No generic IAM or user database; default-off preserves current behavior. Tokens must be loaded from protected files, not property values/arguments, and never logged. Default-disabled feature keeps existing unrelated APIs behavior.

Task 2 is split into sequential 2a (runtime/launcher proof) and 2b (auth/key wiring), following stage-1-boundary-design.md. Repository Git must use the runtime UID too: a disposable core.fsmonitor probe proved backend env access. Local Compose parsing is explicitly unavailable in enabled mode until isolated; no silent fallback.

Real runtime separation must be based on OS enforcement (dedicated runtime UID plus protected control storage/process environment, controlled executable/config/home, preserved workspace roots); no verified=true property. Provide narrow launcher/install packaging and disposable negative canary tests, without installing on this host. Standard provider authentication/session history lives in a stable runtime-owned home. Do not move personal Codex home. No shell network opening. Enabling MCP on a backend that still launches a same-UID unconstrained runtime must fail closed. Configuration/inventory isolation remains Stage 4 for actual MCP injection, but existing home/plugin stdio must not provide a secret bypass in prerequisite deployment.

Tests: correct operator bootstrap/session/CSRF/expiry/logout; wrong audience/runtime bearer, hostile Origin/Host, absent credential and zero application calls; actual synthetic runtime cannot read key/DB/operator/service files or parent environment, cannot write control config or management authority. Correct allowed workspace writes and process cleanup survive; no-MCP default path unchanged. Any unavailable live check is NOT_VERIFIED and blocks enablement acceptance.

## Task 3: Typed management vertical

Agent route `/api/v1/integrations/mcp/connections`; Nexus `/api/v1/infrastructure/agents/integrations/mcp/connections`, existing context `/fgaisox`.
GET collection/detail; POST create; PUT /{id} update; PUT /{id}/enabled with typed boolean; DELETE /{id}; POST /{id}/reencrypt (204) for controlled operator rotation to the configured active key, no secret response. Explicit safe DTOs/mapper/usecase/client, UUID validation. No JsonNode/raw JSON management passthrough. Metadata exposes credentialConfigured only. Runtime policy read is internal application method, not public secret endpoint. Secret requests/errors have redacted string representations.

Agent references ProjectAssetsController/ProjectAssetUseCases; Nexus ForgeAiProjectAssetsController/AgentProxyApiMapper/AgentProjectAssetsUseCase/ForgeAgentClient/ForgeAgentClientAdapter/ForgeAgentHttpClient. New MCP DTOs belong to their respective boundaries. Extend endpoint contracts/fixtures and existing ForgeIT manager, no raw MockMvc/WireMock helper shortcut.

Tests: CRUD status/error mapping and metadata shape; restart persistence; unauthorized Agent/Nexus; invalid input local rejection with zero upstream calls; secret canaries absent from GET/list/error/toString/log captures; no secret/ciphertext accidental serialization. Controlled header/bearer forms use same connection model. Default disabled returns no usable management route.

## Task 4: Integration, operator instructions, review and stop

Run Agent verify + Nexus verify; focused no-MCP runtime regressions; Console unchanged (no new Settings page). Document tested deployment fixture versus NOT_VERIFIED host/live/provider boundaries. Operations notes cover safe key provisioning/rotation and feature default-off; do not deploy. Independent whole-change review and targeted fixes. Stage 1 report contains concrete commands, counts, prerequisite limitations and next Stage 2 plan only. No Stage 2 implementation, push, PR or merge.
