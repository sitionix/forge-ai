# Trigger node infrastructure

## Agreed scope
Add a provider-neutral Trigger node to the existing workflow builder. Its editor selects an MCP connection and then one of that connection's saved trigger configurations. A disabled Add configuration button reserves the future creation flow. No Notion-specific fields, configuration authoring, scheduling, activation, or execution are included.

## Data and ownership
The general Node model gains only the TRIGGER enum value, no configuration fields or reverse association. MCP trigger configuration definitions belong to a connection. A separate node_trigger_bindings row references its node by the definition row's unique storage identity and stores connection_id plus an optional configuration_id. Bindings are reused across runs. Connections/configurations are installation scoped and must permit the workflow project. A configuration must belong to the selected connection. Existing workflow_nodes composite primary keys and workflow-scoped logical IDs remain. A database-only row_id UUID supports the one-column binding foreign key and is never added to Node/API. The repository resolves workflow_id + logical node_id to this stable row identity.

## Builder experience
A Trigger palette item creates one start node with one output and an internal entry input used by the existing graph contract. Hide Task Input and the trigger's internal input on the canvas. Clicking Trigger opens a dedicated editor: Connection, Configuration, and Add configuration (disabled, explained as coming later). No provider-specific settings or agent execution options. Empty/loading/error states are explicit; connection changes clear configuration selection and stale asynchronous responses cannot restore it. Cancel discards edits. Saving a workflow saves the graph then its independent bindings, and reports binding errors without claiming complete success. Reload restores selections. A trigger-only draft may be saved.

## APIs
GET /api/v1/projects/{projectId}/trigger-connections returns eligible installation-owned MCP connections.
GET /api/v1/projects/{projectId}/trigger-connections/{connectionId}/configurations returns that connection's configuration summaries.
GET and PUT /api/v1/workflows/{workflowId}/nodes/{nodeId}/trigger-binding read/write connectionId and nullable configurationId. GET of an unconfigured trigger returns both fields null. PUT with both null clears the binding.
Nexus exposes corresponding /api/v1/infrastructure/agents routes through typed clients.

## Runtime boundary
TRIGGER definitions can be saved and wired, but starting a workflow containing one is rejected explicitly before snapshots or executions are created. No polls, MCP calls, credentials changes, or external board mutations occur.

## Acceptance
Persist/reload a Trigger with a selected connection; list configurations only from that connection; reject cross-project access and cross-connection configuration IDs; preserve existing agent/manual/dialogue behavior; prevent trigger execution; demonstrate empty, loading, error and stale-response UI cases. All new storage must migrate cleanly, including cascade removal of bindings with nodes.
