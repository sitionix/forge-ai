# Agent Execution Event Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist a provider-neutral, fenced, append-only event ledger for new Forge agent turns and expose bounded typed reads through Forge Agent and Nexus.

**Architecture:** Codex structured notifications are normalized by a provider-specific mapper and delivered to an application recorder. The recorder uses a dedicated repository port whose PostgreSQL adapter atomically allocates per-turn sequence values under the existing session fence; capture failures degrade observability without changing execution outcomes. Forge Agent owns the read use case and Nexus remains a typed proxy.

**Tech Stack:** Java 21, Spring Boot 3, Jackson, Spring JDBC, PostgreSQL/Flyway, JUnit 5, Mockito, ForgeIT, Maven.

**Spec:** `docs/superpowers/specs/2026-09-07-agent-execution-event-ledger-design.md`

## Global Constraints

- Preserve the Phase 1B provider conversation then provider turn identity persistence ordering.
- Never write a turn event before `provider_turn_id` is persisted.
- Guard every append and capture-status mutation with `sessionId`, `leaseOwnerId`, and `leaseToken` plus the unexpired database lease.
- The existing completion state machine, `AgentExecutionResult`, routing, NodeRun lifecycle, and session semantics remain authoritative and unchanged.
- Store no hidden reasoning or high-frequency text/reasoning/output deltas.
- Keep event pages bounded to a maximum of 200.
- Do not implement Phase 3 UI or controls.

---

### Task 1: Domain contract and V27 schema

**Files:**
- Create domain event/candidate/type/status/capture/page records under `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/`
- Create `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/AgentExecutionEventRepository.java`
- Create `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/V27__create_agent_execution_event_ledger.sql`
- Create `services/forge-agent/infrastructure/postgres/src/test/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresAgentExecutionEventRepositoryTest.java`

**Interfaces:**
- Produces `AgentExecutionEventCandidate(type,status,phase,providerEventKey,payload,occurredAt)`.
- Produces `AgentExecutionEventRepository.append(claim,candidate)`, `markDegraded(claim)`, `markComplete(claim)`, and `findPage(turnId,afterSequence,limit)`.

- [ ] Write migration/repository integration tests for sequence `1,2,3`, independent sequences, uniqueness, idempotency, repeated unkeyed events, legacy null status, payload/type constraints, immutability, and stale fencing.
- [ ] Run `mvn -B -ntp -pl services/forge-agent/infrastructure/postgres -am -Dtest=PostgresAgentExecutionEventRepositoryTest test` and confirm the new contract fails because schema/types are absent.
- [ ] Add the domain types, repository port, and V27 schema with composite correlation integrity and immutable-event trigger.
- [ ] Implement `PostgresAgentExecutionEventRepository` with one-transaction fenced counter allocation/insertion and bounded ascending reads.
- [ ] Re-run the focused test and confirm it passes.
- [ ] Commit as `feat: add agent execution event ledger persistence`.

### Task 2: Application recorder and capture degradation

**Files:**
- Create `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentExecutionEventRecorder.java`
- Create `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/AgentExecutionEventUseCases.java`
- Create corresponding tests under `services/forge-agent/application/src/test/java/`
- Modify Phase 1B turn allocation/persist-turn code only to initialize and activate capture metadata.

**Interfaces:**
- Consumes the Task 1 repository port and `AgentSessionExecutionClaim`.
- Produces non-throwing observability methods `record`, `complete`, and explicit activation after identity persistence.

- [ ] Write failing tests proving append errors mark `DEGRADED`, degradation failures remain non-fatal, stale fences reject writes, `DEGRADED` is sticky, and pagination validates/defaults/caps limits.
- [ ] Run the focused application tests and verify expected failures.
- [ ] Implement the recorder/use case and activate capture only after successful provider turn persistence.
- [ ] Re-run application tests and existing `AgentSessionLeaseServiceTest`.
- [ ] Commit as `feat: record agent event capture health`.

### Task 3: Codex semantic mapper and safe payload normalization

**Files:**
- Create `CodexAgentExecutionEventMapper`, `CodexEventPayloadSanitizer`, and tests in `services/forge-agent/infrastructure/codex/`.
- Extend `CodexProtocol` only with audited structured method constants.

**Interfaces:**
- Consumes Codex method plus params.
- Produces zero or one `AgentExecutionEventCandidate`; unsupported deltas produce empty.

- [ ] Add literal fixtures for plan, reasoning summary, command lifecycle including failure, file change, MCP/tool call, final agent message, warning/error, token usage, context compaction, and ignored high-frequency deltas.
- [ ] Add fixtures proving truncation metadata and credential redaction.
- [ ] Run `mvn -B -ntp -pl services/forge-agent/infrastructure/codex -am -Dtest=CodexAgentExecutionEventMapperTest test` and verify failure before implementation.
- [ ] Implement minimal field-whitelist mapping, deterministic keys, UTF-8 bounds, and sanitizer.
- [ ] Re-run mapper tests and confirm pass.
- [ ] Commit as `feat: map Codex activity to Forge events`.

### Task 4: Tracked execution integration and completion observation

**Files:**
- Modify `CodexExecutionIdentityCallbacks`, `CodexClient`, `CodexAppServerClient`, `CodexTurnStateTracker`, and `CodexAgentExecutor`.
- Extend `CodexAppServerTurnClientTest`, `CodexTurnStateTrackerTest`, and `CodexAgentExecutorTest`.

**Interfaces:**
- Consumes Task 2 recorder and Task 3 mapper.
- Delivers provider candidates only after `turnStarted` identity callback succeeds; reports semantic completion after the existing tracker completes.

- [ ] Add failing tests for pre-response buffering/ordered flush, identity failure discard, result equality with capture enabled, fallback completion producing exactly one terminal event, schema completion deduplication, and failed command followed by success.
- [ ] Run the three focused Codex suites and verify the new assertions fail while old assertions stay green.
- [ ] Add a per-execution buffered observer, flush it after `persistTurn`, and discard it on identity failure.
- [ ] Wire semantic terminal observation to the existing completion result without changing its decision branches.
- [ ] Re-run all Codex module tests including current completion regression tests.
- [ ] Commit as `feat: capture tracked Codex execution events`.

### Task 5: Forge Agent read API

**Files:**
- Create event page/event DTOs and controller under `services/forge-agent/api-rest/`.
- Extend `ForgeAgentApiMapper` and its tests.
- Add controller tests for route, cursor, limits, capture status, legacy null, and not found.

**Interfaces:**
- Consumes `AgentExecutionEventUseCases.page(turnId,afterSequence,limit)`.
- Produces provider-neutral JSON with `turnId`, `captureStatus`, ascending events, cursor fields, and `hasMore`.

- [ ] Write failing mapper/controller tests using literal provider-neutral responses.
- [ ] Run focused api-rest tests and verify missing DTO/route failures.
- [ ] Implement DTOs, mapper, and `GET /api/v1/agent-execution-turns/{turnId}/events`.
- [ ] Re-run all Forge Agent api-rest tests.
- [ ] Commit as `feat: expose agent turn event pages`.

### Task 6: Typed Nexus proxy

**Files:**
- Add provider-neutral event domain models and use-case interface in `services/forge-nexus/domain/`.
- Add use-case implementation in `services/forge-nexus/application/`.
- Add client DTOs, HTTP method, adapter method, and mapper in `services/forge-nexus/clients/agent-client/`.
- Add Nexus REST DTO/controller/mapper in `services/forge-nexus/api-rest/`.
- Extend client, mapper, controller, and `NexusAgentProxyIT` coverage.

**Interfaces:**
- Adds `ForgeAgentClient.getAgentExecutionEvents(turnId,afterSequence,limit)`.
- Produces the Nexus route `/api/v1/infrastructure/agents/agent-execution-turns/{turnId}/events` with no provider interpretation.

- [ ] Write failing client mapping, use-case, controller, and WireMock proxy tests for ascending events, cursor, page limit, capture status, and legacy `UNAVAILABLE`.
- [ ] Run focused Nexus module tests and verify failures.
- [ ] Implement the typed forwarding chain and DTO mappings.
- [ ] Re-run all affected Nexus module tests.
- [ ] Commit as `feat: proxy agent turn event pages through Nexus`.

### Task 7: Gated live E2E and full regression verification

**Files:**
- Extend or create a gated test under `services/forge-agent/infrastructure/codex/src/test/java/`.
- Extend boot integration fixtures only where persistence-backed identity evidence requires them.
- Update the design/plan if implementation names differ while preserving the approved contract.

**Interfaces:**
- Exercises a real tracked Codex `0.153.2` turn and reads the Forge repository using its actual turn UUID.

- [ ] Add a gated live test whose deterministic prompt requires one shell command and final JSON output, and assert ordered start/activity/message/completion plus optional usage.
- [ ] Run all explicit Phase 1B session, completion, heartbeat, fencing, and routing tests.
- [ ] Run `mvn -B -ntp -pl services/forge-agent/boot -am verify`.
- [ ] Run `mvn -B -ntp -pl services/forge-nexus/boot -am verify`.
- [ ] Run the explicitly gated live Codex ledger E2E separately and retain its event evidence.
- [ ] Run `git diff --check`, inspect the complete diff, and confirm no Phase 3 UI or unrelated refactor entered the branch.
- [ ] Commit final test/document adjustments as `test: verify agent execution event ledger`.
- [ ] Push `feature/SITIONIX-113` and open a PR against `main` with the verification evidence.
