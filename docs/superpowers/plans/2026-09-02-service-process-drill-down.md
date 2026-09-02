# Service Process Drill-down Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an on-demand Top 5 process drill-down scoped to the selected running systemd service cgroup.

**Architecture:** Agent owns validation, cgroup membership, bounded procfs sampling, normalization, sorting, and truncation behind a typed port. Nexus remains a typed map/execute/map proxy. Console owns one expanded row and an independent stale-safe request lifecycle without process polling.

**Tech Stack:** Java 21, Spring MVC/HTTP interfaces, Maven, JavaScript, Vitest/JSDOM, ForgeIT/WireMock.

**Spec:** `docs/superpowers/specs/2026-09-02-service-process-drill-down-design.md`

## Global Constraints

- Do not alter the existing service-metrics snapshot or its four-second polling behavior.
- Process membership must come from the selected systemd service cgroup and descendants, never process names.
- CPU uses bounded counter deltas and total-host normalization; unavailable values stay `null`.
- Agent returns at most five processes sorted by requested `cpu` or `ram`; default is `cpu`.
- Nexus performs only typed map → execute → map and forwards identifiers, unit, and sort unchanged.
- No persistence, history, background monitoring, arbitrary command API, process actions, or database changes.
- Preserve the user's existing `.gitignore` modification.

---

### Task 1: Agent typed process endpoint and ownership

**Files:**
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/ProcessMetricsSort.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/ServiceProcessMetrics.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/ServiceProcessMetricsSnapshot.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/ServiceProcessMetricsPort.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/SshConnectionUseCases.java`
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/SshConnectionsController.java`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/usecase/SshConnectionUseCasesTest.java`

**Interfaces:**
- Produces: `ServiceProcessMetricsSnapshot serviceProcesses(UUID projectId, UUID connectionId, String unit, ProcessMetricsSort sort)`.
- Produces: `ServiceProcessMetricsPort.collect(SshConnection connection, String unit, ProcessMetricsSort sort)`.

- [ ] **Step 1: Write failing ownership, selected-connection, and invalid-unit tests**

Add tests proving the exact persisted connection reaches the new port, a cross-project connection throws `SSH_CONNECTION_NOT_FOUND`, and `bad.service; touch /tmp/pwned` is rejected before the port is called.

- [ ] **Step 2: Run the Agent application tests and verify RED**

Run: `mvn -pl application -am -Dtest=SshConnectionUseCasesTest -Dsurefire.failIfNoSpecifiedTests=false test` from `services/forge-agent`.

Expected: compilation/test failure because the process models, port, and use-case method do not exist.

- [ ] **Step 3: Add minimal typed models, port, unit validation, use case, and controller route**

Use a typed enum with `CPU`/`RAM`, records with nullable boxed measurements, the existing `ownedConnection`, and a strict systemd-unit regex. Add `GET /{connectionId}/service-metrics/{unit}/processes` with `sort=cpu` default.

- [ ] **Step 4: Run the Agent application tests and verify GREEN**

Run the Step 2 command and require exit 0.

### Task 2: Agent cgroup/procfs probe

**Files:**
- Create: `services/forge-agent/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/CliServiceProcessMetricsAdapter.java`
- Create: `services/forge-agent/infrastructure/local/src/test/java/com/sitionix/forgeagent/infrastructure/local/CliServiceProcessMetricsAdapterTest.java`

**Interfaces:**
- Consumes: `ServiceProcessMetricsPort.collect(SshConnection, String, ProcessMetricsSort)`.
- Produces: structured parsing and Top 5 typed snapshots from a single bounded SSH probe.

- [ ] **Step 1: Write failing fixed-executor tests**

Cover descendant cgroup membership and unrelated exclusion through emitted fixtures, CPU deltas normalized against host ticks, RSS KiB-to-bytes conversion, threads, CPU Top 5, RAM Top 5, null-last handling, empty success, malformed/probe failure, and command construction where the unit is a positional argument rather than script interpolation.

- [ ] **Step 2: Run the local-infrastructure test and verify RED**

Run: `mvn -pl infrastructure/local -am -Dtest=CliServiceProcessMetricsAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test` from `services/forge-agent`.

Expected: compilation failure because the adapter does not exist.

- [ ] **Step 3: Implement the bounded cgroup/procfs probe and parser**

Resolve `LoadState`, `ActiveState`, and `ControlGroup`; enumerate descendant `cgroup.procs`; take two `/proc/stat` and `/proc/PID/stat` samples separated by a bounded sleep; emit framed tab-separated fields; parse nullable values; calculate CPU; sort null-last; truncate to five. Use the existing typed executor so non-zero remote execution retains typed failure behavior.

- [ ] **Step 4: Run the local-infrastructure and Agent aggregate tests**

Run the Step 2 command, then `mvn test` from `services/forge-agent`; require exit 0.

### Task 3: Nexus typed proxy and ForgeIT

**Files:**
- Create typed process records under `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/model/agentproxy/`, `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/dto/`, and `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/agentproxy/`.
- Modify: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/port/ForgeAgentClient.java`
- Modify: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/usecase/ManageAgentProjectSshConnections.java`
- Modify: `services/forge-nexus/application/src/main/java/com/sitionix/forgeai/application/agentproxy/AgentProjectSshConnectionsUseCase.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentHttpClient.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentClientAdapter.java`
- Modify: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiProjectSshConnectionsController.java`
- Modify: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/agentproxy/AgentProxyApiMapper.java`
- Modify tests beside those classes plus `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/NexusAgentProxyIT.java` and endpoint/JSON fixtures.

**Interfaces:**
- Consumes: Agent process endpoint with unchanged path/query values.
- Produces: Nexus process endpoint with the same typed response fields.

- [ ] **Step 1: Write failing client-adapter, controller/mapper, and ForgeIT tests**

Assert literal response values and exact `projectId`, `connectionId`, `unit`, and sort forwarding. Add matching upstream/public JSON fixtures with nullable measurements.

- [ ] **Step 2: Run targeted Nexus tests and verify RED**

Run: `mvn -pl clients/agent-client,api-rest,boot -am -Dtest=ForgeAgentClientAdapterTest,ForgeAiProjectSshConnectionsControllerTest,AgentProxyApiMapperTest,NexusAgentProxyIT -Dsurefire.failIfNoSpecifiedTests=false test` from `services/forge-nexus`.

- [ ] **Step 3: Implement minimal typed map/execute/map plumbing**

Add records and one method per existing proxy boundary. Do not add calculations, caches, retries, persistence, or failure reinterpretation.

- [ ] **Step 4: Run targeted and aggregate Nexus tests**

Run the Step 2 command, then `mvn test` from `services/forge-nexus`; require exit 0.

### Task 4: Console expandable process detail

**Files:**
- Modify: `services/forge-console/src/operator/agent-projects-api.js`
- Modify: `services/forge-console/src/operator/agent-projects-api.d.ts`
- Modify: `services/forge-console/src/operator/system-health-view.js`
- Modify: `services/forge-console/src/operator/operator-ui.css`
- Modify: `services/forge-console/tests/system-health-view.test.ts`
- Modify API contract test in `services/forge-console/tests/agent-projects-page.test.ts`.

**Interfaces:**
- Consumes: `getSshConnectionServiceProcesses(projectId, connectionId, unit, sort)`.
- Produces: one inline expanded row with loading/error/empty/data states and CPU/RAM selector.

- [ ] **Step 1: Write failing DOM and API tests**

Cover no request before click, exact expansion/collapse/switch behavior, Top 5 rendering, CPU/RAM query selection, loading/error/empty states, non-overlap, stale response rejection after unit/connection/project changes, and cleanup on close/dispose.

- [ ] **Step 2: Run Console tests and verify RED**

Run: `npm test -- --run tests/system-health-view.test.ts tests/agent-projects-page.test.ts` from `services/forge-console`.

- [ ] **Step 3: Implement API call, state machine, inline markup, and styles**

Add an independent process generation/in-flight identity, clear it on every relevant lifecycle, issue requests only on expansion/sort change, and keep host/service markup visible on process errors.

- [ ] **Step 4: Run targeted and full Console checks**

Run the Step 2 command, then `npm test -- --run` and `npm run build` from `services/forge-console`; require exit 0.

### Task 5: Cross-layer verification and review

**Files:**
- Review all changed files; do not modify unrelated files.

**Interfaces:**
- Verifies the acceptance criteria end-to-end at typed boundaries.

- [ ] **Step 1: Run formatting/diff checks**

Run: `git diff --check` and inspect `git status --short` plus `git diff --stat`.

- [ ] **Step 2: Run fresh Agent, Nexus, and Console verification**

Run `mvn test` in Agent and Nexus, then `npm test -- --run` and `npm run build` in Console.

- [ ] **Step 3: Review requirements line by line**

Confirm ownership, cgroup membership, unrelated exclusion, current normalized CPU, nullable data, Top 5 both sorts, typed failures, injection resistance, Nexus forwarding, on-demand lifecycle, stale rejection, and unchanged host/service polling are each backed by implementation and tests.

- [ ] **Step 4: Commit only task files**

Stage explicit paths, leaving `.gitignore` unstaged, and commit with `feat: add service process drill-down`.
