# Trigger Infrastructure Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan inline, then obtain one whole-change review.

**Goal:** Save a provider-neutral Trigger node with an MCP connection and an existing connection-owned configuration selection.
**Architecture:** Keep configuration outside Node. Independent typed endpoints own bindings and connection configuration listings; the builder has a dedicated editor module. Existing graph serialization remains unchanged.
**Tech Stack:** Java 21, Spring, PostgreSQL/Flyway, browser JavaScript, Vitest.
**Spec:** docs/superpowers/specs/2026-10-19-trigger-infrastructure-design.md

## Global Constraints
- Use regular branch feature/SITIONIX-163; preserve unrelated tools/ files.
- No Notion fields, scheduling, execution, creation screen or generic config property on Node.
- Bindings reference only the unique database row identity through node_row_id; no workflow_id in binding storage.
- UI text follows existing English console.

## Review Focus
- Connection or configuration belongs to another project/connection: reject.
- Connection/configuration request completes after selection changes: ignore stale result.
- Binding save fails after graph save: show partial-save error and retain local selection for retry.
- Previously saved selection becomes inaccessible: show error rather than silently clear it.
- Trigger is present in a run request: reject before provider execution or snapshot creation.

### Task 1: Trigger graph boundary
Files: NodeType.java, WorkflowGraphValidator.java, WorkflowRunUseCases.java, V47 migration, graph/runtime tests.
- [x] Add failing tests for non-agent Trigger entry, trigger shape and explicit run rejection; run targeted Maven tests and observe failure.
- [x] Add TRIGGER graph validation and persistence constraints; block execution before snapshot building.
- [x] Run application tests and migration coverage.

### Task 2: Connection configurations and independent node binding
Files: domain trigger records/port, application TriggerBindingService, postgres adapter, agent controller, Nexus typed client/use case/controller.
Interfaces: listConnections(projectId), listConfigurations(projectId, connectionId), getBinding(workflowId,nodeId), saveBinding(workflowId,nodeId,connectionId,configurationId).
- [x] Add failing service tests for access and configuration ownership plus persistence round trip coverage.
- [x] Implement storage, service, endpoints and typed Nexus forwarding.
- [x] Verify save/reload/clear and no cross-project/configuration leakage.

### Task 3: Builder editor
Files: workflow-builder.js, new trigger-node-editor.js, agent-projects-api.js, agent-projects.html, Vitest tests.
- [x] Add failing tests for adding a trigger and selection/reset/stale-response/save behavior.
- [x] Implement connection/configuration editor with loading, empty and retryable error states; Add configuration stays disabled.
- [x] Integrate graph saving and binding saving, reload and cancellation.
- [x] Run full Console tests, typecheck and build.

### Task 4: Verification and review
- [x] Run Agent and Nexus checks, migration tests and git diff --check.
- [x] Rebuild the local application and verify the actual builder with persisted Trigger data; remove only session-created review fixtures.
- [x] Obtain one independent whole-change review and address important findings.
