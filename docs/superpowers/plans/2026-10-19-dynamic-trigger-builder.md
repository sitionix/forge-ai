# Dynamic MCP Trigger Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for inline implementation or superpowers:subagent-driven-development if selected by the user. Complete each task and its tests before the next task.

**Goal:** Author reusable, connection-owned Trigger configurations using forms generated from MCP schemas.
**Architecture:** Extend the existing inventory with input/output schemas, add connection-scoped configuration authoring, and compose a schema renderer inside an independent Console editor. Trigger nodes continue selecting an existing configuration ID; no execution engine is added.
**Tech Stack:** Java 21, Spring, PostgreSQL/Flyway, existing networknt json-schema-validator 2.0.4, browser JavaScript, Vitest and ForgeIT.
**Spec:** docs/superpowers/specs/2026-10-19-dynamic-trigger-builder-design.md

## Global Constraints
- Use a regular branch in the current checkout, no worktree. Before implementation, update main by fast-forward and derive the next feature/SITIONIX number from local and remote branches. Preserve unrelated tools/ content and the two design/plan documents.
- No hardcoded provider/tool/argument names, scripts, automatic discovery calls, live MCP operations, scheduling or workflow execution.
- Reuse MCP approval fingerprints and project permissions; missing schemas remain distinguishable from empty schemas.
- Reuse mcp_trigger_configurations. Do not change Node or node_trigger_bindings.
- Existing UI copy is English. No new third-party dependencies are required.

## Review Focus
- A missing schema, false/zero/empty-string parameter, and an omitted optional parameter must stay distinct (tasks 1/3).
- Reordering/removing a step must not silently rebind references or leave a valid-looking forward reference (tasks 2/3).
- Switching connections or closing the editor during an HTTP request must discard stale responses while retaining unsaved data on a real save failure (task 3).
- Unknown schema keywords must not cause silent data loss; external schema URLs must not trigger network fetches (tasks 2/3).
- A removed approval or changed tool fingerprint must fail save without overwriting an existing configuration (task 2).

## Task 1: Carry discovered tool schemas through inventory

**Files:**
- Modify Agent domain/model/McpToolSummary.java, infrastructure/local/.../mcp/protocol/SdkMcpRemoteClient.java, infrastructure/postgres/.../adapter/PostgresMcpToolInventoryRepository.java and api-rest/.../mcp/McpProbeResponse.java.
- Modify Nexus domain/model/mcp/McpProbeReport.java, clients/agent-client/.../dto/McpProbeInboundResponse.java, existing MCP adapter mappings, api-rest/.../mcp/McpProbeResponse.java and ForgeAiMcpConnectionsController.java.
- Create Agent infrastructure/postgres/src/main/resources/db/migration/V48__add_mcp_tool_schemas.sql, after confirming V48 is still free at execution time.
- Extend existing MCP protocol, inventory PostgreSQL and Nexus proxy tests/fixtures.

**Interfaces:** McpToolSummary gains nullable Map<String,Object> inputSchema and outputSchema; preserve its existing three-argument constructor for legacy summaries. Public and upstream tool responses expose nullable inputSchema/outputSchema JSON objects. Existing list/test routes and fingerprint semantics remain unchanged. SDK 2.0.1 already provides both Tool.inputSchema() and Tool.outputSchema().

- [x] Write tests that preserve nested schemas and optional output schemas end-to-end, including a legacy database row with no schemas. Verify changing schema transport does not change the existing input-schema fingerprint or tool approval behavior.
- [x] Run focused protocol/inventory/Nexus fixtures and observe failure because schemas are currently discarded.
- [x] Add nullable JSONB inventory columns and propagate schemas through existing adapters and typed DTOs. Do not fetch inventory when merely opening the builder.
- [x] Rerun focused tests; verify legacy rows remain readable and need an explicit existing connection check before generated editing becomes available.
- [x] Inspect and commit only task-owned changes on the feature branch.

## Task 2: Persist and validate connection-owned configuration documents

**Files:**
- Create Agent domain/model/TriggerConfigurationDocument.java and domain/port/TriggerConfigurationRepository.java.
- Create application/trigger/TriggerConfigurationService.java and TriggerConfigurationValidator.java.
- Create infrastructure/postgres/.../adapter/PostgresTriggerConfigurationRepository.java and migration V49__add_trigger_configuration_definitions.sql (adjust only if the migration slot changed).
- Create api-rest/.../trigger/McpTriggerConfigurationController.java.
- Extend Nexus domain trigger model/port/usecase and existing WorkflowTriggerUseCase, WorkflowTriggerClientAdapter, ForgeAgentHttpClient; add api-rest/.../trigger/McpTriggerConfigurationController.java to the existing infrastructure exception advice.
- Add service/validator tests, Agent ForgeIT persistence tests, and typed Nexus ForgeIT routes/fixtures in the existing endpoint layout.

**Interfaces:** Agent prefix /api/v1/integrations/mcp/connections/{connectionId}/trigger-configurations; Nexus adds /infrastructure/agents after /api/v1. GET collection returns id/connectionId/name summaries; POST creates; GET /{configurationId} loads; PUT /{configurationId} updates. Request is {name,definition}; detail response is {id,connectionId,name,definition}. Domain document stores definitionJson as String; REST and PostgreSQL boundaries encode/decode JSON objects.

**Definition v1:** {version:1,steps:[{id,toolName,schemaFingerprint,arguments,bindings:[{targetPointer,sourceStepId,sourcePointer}]}],result:{stepId,pointer}}. IDs are stable local UUIDs, arguments is literal JSON object, pointers use JSON Pointer with the empty string selecting the whole value. All tools belong to the owning connection; no cross-connection steps. No optional expression language.

- [x] Write tests for create/load/update, round-trip JSON types/nulls, legacy summary-only configurations, foreign connection IDs, missing/removed approvals, stale schema fingerprints, duplicate step IDs, unknown version, invalid pointers, conflicting binding targets and forward/deleted-step references. Failed updates must preserve the prior saved definition.
- [x] Run those tests RED, then implement the document repository, transactional ownership checks and validation using existing installation identity and MCP inventory/approval repositories.
- [x] Reuse the installed JSON Schema validator with external schema retrieval disabled. Validate complete literal-only arguments normally. For bound parameters validate the reference and provided literal parameter schemas, and explicitly defer constraints that require runtime values; never claim a bound result was remotely validated. Local schema references remain within the provided schema document; unresolved references produce a clear validation error.
- [x] Add the JSONB definition column without inventing definitions for existing metadata-only configurations. Return an explicit missing-definition state for editing such legacy rows.
- [x] Implement typed Agent/Nexus routes and error forwarding. Retain the existing project-scoped summaries used by the Trigger node.
- [x] Verify via ForgeIT: exact upstream routes and calls, typed body/response round trips, null preservation, cross-connection rejection and upstream 400/404 propagation.
- [x] Inspect and commit task-owned changes.

## Task 3: Schema form and configuration editor

**Files:**
- Create services/forge-console/src/operator/mcp-schema-form.js and trigger-configuration-editor.js, with focused tests in services/forge-console/tests/.
- Modify mcp-api.js and its declarations, settings-page.js/settings.html, mcp-connections-view.js and relevant styles using existing components.
- Modify trigger-node-editor.js to activate Add configuration navigation to the selected connection; add focused integration tests for this navigation and selecting newly saved summaries.

**Interfaces:** McpSchemaForm({document,schema,value,onChange}) exposes render(container), readValue(), and dispose(). Literal values use normal JSON types. TriggerConfigurationEditor receives the existing McpApi and selected connection/tool inventory; it owns the draft, step selection and save lifecycle. McpApi gains listTriggerConfigurations(connectionId), getTriggerConfiguration(connectionId,id), createTriggerConfiguration(connectionId,request), updateTriggerConfiguration(connectionId,id,request), using existing abort-signal conventions. Settings accepts a connectionId navigation parameter and opens that connection through the normal details flow.

- [x] Write failing renderer tests using unrelated synthetic tools: required/optional strings, integers/numbers, false/zero/empty string, enums, nullable values, nested objects/arrays, additional properties, local references and schema alternatives. No test or production renderer may choose behavior based on provider/tool/property names.
- [x] Implement recursive schema-derived controls, descriptions, enum choices, explicit optional omission and null controls. Render oneOf/anyOf as an alternative selector without discarding hidden data silently. Where a construct cannot be rendered faithfully, show an explicit generic JSON input and rely on schema validation; do not guess a provider-specific UI.
- [x] Write failing editor tests for add/edit/reorder/remove steps, stable source references, result selection, no approved tools, absent schemas, unknown output structure, cancellation, stale async results, failed-save draft retention and reopening saved definitions.
- [x] Implement the editor in connection details with a configuration list. Literal/reference selection belongs to each parameter. Known output schemas provide a field tree; unknown outputs offer whole result or an explicit JSON Pointer. Invalidate affected bindings visibly when a tool or ordering changes; do not silently repair references.
- [x] Connect Add configuration in the Trigger node to MCP settings without automatically saving or discarding a dirty workflow; require the existing workflow save before navigation when necessary. A save failure keeps the builder and draft open. After authoring, the existing node editor reloads summaries when reopened.
- [x] Run focused Console tests, full npm test, npm run typecheck and npm run build. Inspect and commit task-owned changes.

## Task 4: End-to-end verification and review

- [x] Verify migration upgrades and full Agent Maven verify (local Docker requires -Dapi.version=1.44); full Nexus verify including ForgeIT; full Console tests/typecheck/build; git diff --check.
- [x] Inspect the final diff against the spec. Confirm there are no provider-specific branches, execution paths or changes to unrelated tools/ files.
- [x] Verify the actual editor in the local browser with synthetic test inventory/configuration data only; no live MCP calls or modifications to external connections. Remove only session-created fixtures.
- [x] Obtain one independent whole-change review, address important findings, and report the exact verification results and remaining UI limits. Do not push, create a PR or merge without a subsequent user instruction.

## Verified outcome

Implemented on feature/SITIONIX-164. Full Agent/Nexus Maven verify passed; Console796/796, typecheck/build and diff checks passed. Independent review accepted after regression fixes. Browser verified schema-derived controls, nested enum fields, defaults and step creation using a disabled synthetic connection. Browser automation became blocked at a native confirmation, so save/reload was verified by UI tests, native ForgeIT and an additional deployed-local-API create/list/reload/update smoke with two steps and a binding. This is not a claim of a completed browser save/reload flow. The synthetic connection and its configurations were removed; no live MCP calls were made.
