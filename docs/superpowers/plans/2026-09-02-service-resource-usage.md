# Service Resource Usage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show live whole-service systemd CPU, RAM, and task usage for the selected saved SSH connection.

**Architecture:** Agent resolves and authorizes the saved SSH connection, then a dedicated port/CLI adapter returns a stateless systemd snapshot. Nexus forwards this through typed layers. Console independently polls snapshots and derives host-normalized CPU deltas before rendering the existing Additional info panel.

**Tech Stack:** Java 21, Spring Boot, OpenFeign, JUnit 5/Mockito/AssertJ, ForgeIT, browser JavaScript, Vitest/JSDOM.

**Spec:** `docs/superpowers/specs/2026-09-02-service-resource-usage-design.md`

## Global Constraints

- Do not persist samples or introduce monitoring/history or arbitrary command APIs.
- Use saved `SshConnection` ownership and whole systemd service cgroups.
- Preserve unavailable values as null/`—` and leave Project Logs/SSE unchanged.
- Service polling is independent, non-overlapping, stale-safe, and disposed with the view.

---

### Task 1: Agent service snapshot

**Files:**
- Create: Agent `ServiceMetricsSnapshot`, `ServiceResourceMetrics`, and `ServiceMetricsPort` domain files.
- Create: `services/forge-agent/infrastructure/local/.../CliServiceMetricsAdapter.java`
- Modify: `SshConnectionUseCases`, `SshConnectionsController`.
- Test: corresponding application, adapter, and Agent ForgeIT tests/fixtures.

**Interfaces:**
- Produces: `ServiceMetricsSnapshot serviceMetrics(UUID projectId, UUID connectionId)` and `ServiceMetricsPort.collect(SshConnection)`.

- [ ] Add failing ownership/controller/collector tests covering running services, nullable values, and SSH execution.
- [ ] Run focused Agent tests and confirm failures are caused by the missing feature.
- [ ] Add the minimal typed models, port, use case, endpoint, and systemd CLI parser.
- [ ] Run focused Agent tests to green and refactor without widening scope.

### Task 2: Typed Nexus proxy

**Files:**
- Create: Nexus domain/API/client service-metrics records.
- Modify: `ForgeAgentClient`, `ForgeAgentHttpClient`, client mapper/adapter, SSH use-case/controller/API mapper.
- Test: Nexus controller/client tests and ForgeIT WireMock/MockMvc fixtures.

**Interfaces:**
- Consumes: Agent `GET /api/v1/projects/{projectId}/ssh-connections/{connectionId}/service-metrics`.
- Produces: Nexus `GET /api/v1/infrastructure/agents/projects/{projectId}/ssh-connections/{connectionId}/service-metrics`.

- [ ] Add failing client/controller/ForgeIT tests with literal identifiers and response values.
- [ ] Run focused Nexus tests and confirm expected missing-contract failures.
- [ ] Implement typed DTO mapping and unchanged identifier forwarding.
- [ ] Run focused Nexus tests to green.

### Task 3: Console polling, calculation, and table

**Files:**
- Modify: `agent-projects-api.js`, `system-health-view.js`, `operator-ui.css`.
- Test: `services/forge-console/tests/system-health-view.test.ts` and API tests where present.

**Interfaces:**
- Consumes: `{ sampledAt, services: [{ unit, description, cpuUsageNanos, memoryBytes, tasks }] }` plus host core count/RAM.
- Produces: independently polled Top 3/expanded sortable table and exported pure CPU calculation helper.

- [ ] Add failing tests for delta normalization/unavailable values and all requested view/poll transitions.
- [ ] Run focused Vitest and confirm feature-absence failures.
- [ ] Add API method, independent polling state, pure derivation/sorting helpers, rendering, and scoped CSS.
- [ ] Run Console tests to green and refactor.

### Task 4: Full verification

- [ ] Run relevant full Agent, Nexus, and Console suites.
- [ ] Run formatting/static checks used by the repository and `git diff --check`.
- [ ] Audit every acceptance criterion against tests and inspect the final diff for unrelated changes.
- [ ] Commit the implementation with a focused message.
