# Dynamic MCP Trigger Configuration Builder — design for review

Status: implemented locally on feature/SITIONIX-164; execution and scheduling remain outside this increment.

## Intent and scope

Create and edit a reusable trigger configuration from the MCP connection details screen. The user chooses tools and fills schema-derived forms, without writing scripts. The existing Trigger node chooses the saved configuration. No provider-specific fields, tool names, board/status assumptions, or service-specific UI branches.

This increment authors configuration only. It does not activate a trigger, schedule checks, invoke MCP tools, or start workflows. Opening or saving the builder must not execute remote actions. Existing connection approval and project access rules remain authoritative.

## User flow

1. Open an MCP connection and its Trigger configurations section.
2. Add a configuration and give it a name.
3. Add an ordered step: choose an approved tool discovered on this connection. Display its actual name and description.
4. Render its parameters from its input schema. Required fields, descriptions, defaults and enumerated choices come from the schema.
5. Add another step if needed. A parameter can contain a literal value or reference the result of an earlier step.
6. Choose the configuration result: the complete result of a selected step or a field within it. This declares the data a future runner would pass into the workflow.
7. Save. The configuration becomes selectable in the existing Trigger node editor and can be reopened for editing.

The existing Add configuration button leads to the selected MCP connection's configuration authoring screen. Trigger node editing continues to contain only connection/configuration selection.

## Dynamic form contract

The renderer understands schema structure, not integration names. Basic controls cover strings, numbers, integers, booleans, enums, nested objects, arrays, optional values and nullable values. Nested structures use expandable groups and add/remove controls. Schema-defined alternatives present a choice between alternatives. Unsupported schema constructs must be explicitly identified; a generic JSON input can be offered for that parameter, but it must still pass supported validation and must not be presented as a fully generated friendly form.

Board, database and status dropdowns are not invented from descriptions. A schema enum is a dropdown; an unconstrained string remains an input. Dynamic discovery of external resources through additional MCP calls is a separate future increment.

Users select a preceding step as a value source. Where output structure is known, show a field tree. Otherwise, allow the whole result or an explicit JSON Pointer; do not invent an output structure. No arbitrary expressions, JavaScript, eval, or provider-specific transformation language.

## Data and API boundaries

Preserve full input schemas through the existing MCP discovery/inventory path: SDK adapter, domain, PostgreSQL storage, Agent REST, Nexus typed client/API and Console. Existing inventory rows without a schema remain readable but cannot supply a generated form until inventory is refreshed through the existing connection check action. The builder itself does not refresh remote inventory automatically.

Reuse mcp_trigger_configurations. Add a versioned definition document containing ordered steps and the result selection. Each step has a stable local ID, tool name, schema fingerprint, literal arguments, and explicit argument bindings referencing an earlier step and JSON Pointer. Bindings are separate from literal JSON, so ordinary user objects cannot accidentally become executable reference markers. The connection is owned by the configuration row; do not duplicate workflow/node ownership in the definition.

Add connection-scoped create/read/update operations through Agent and the existing typed Nexus proxy. Retain the existing configuration summary listing used by the Trigger editor. No changes to Node or node_trigger_bindings.

Validate configuration names, document version, step IDs/order, discovered and approved tool fingerprints, literal argument types/constraints, binding target paths and references to earlier steps. References with unknown output types remain explicit references; saving a definition does not claim that a remote result has been validated. Unchanged existing MCP approval fingerprints retain their meaning. A changed tool schema must be surfaced rather than silently adopting the new contract.

## Implementation shape

Keep the schema-driven form and the configuration editor in dedicated Console modules, integrated into the existing MCP connection details flow. Keep configuration persistence/validation separate from the existing node binding service. Extend existing MCP inventory models and typed API layers; do not add a second MCP discovery stack or a general workflow engine.

## Verification and acceptance

Use synthetic MCP tool schemas for unrelated example tools, including nested inputs, arrays, enums and nullable fields. The same renderer must support them without inspecting provider/tool/field names. Verify create/reopen/edit, step reordering/reference validation, precise null/type handling, missing and changed schemas, existing approved-tool restrictions, and selection through the Trigger node.

Follow native Agent and Nexus ForgeIT endpoint contracts and fixtures, including error forwarding and expected upstream-call verification. Run Console tests/typecheck/build, affected Agent checks, Nexus verify and diff checks. No live MCP operations or external connection changes are required.

## Limits of this increment

This is a reusable configuration authoring foundation. Polling, no-item behavior, selecting one item from a collection, conditionals, retries, deduplication and automatic workflow execution require a later runner design; an ordered MCP-call definition alone must not be described as an operational Notion grooming trigger.
