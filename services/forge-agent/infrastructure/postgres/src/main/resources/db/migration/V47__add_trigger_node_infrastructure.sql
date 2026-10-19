-- A binding belongs to one definition node and is reused by future runs.
ALTER TABLE workflow_nodes ADD COLUMN row_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_nodes ADD CONSTRAINT uq_workflow_nodes_row_id UNIQUE (row_id);
ALTER TABLE workflow_nodes DROP CONSTRAINT chk_workflow_nodes_node_type,
    ADD CONSTRAINT chk_workflow_nodes_node_type CHECK (node_type IN ('AGENT','MANUAL','DIALOGUE','TRIGGER')),
    DROP CONSTRAINT chk_workflow_nodes_type_target,
    ADD CONSTRAINT chk_workflow_nodes_type_target CHECK (
        (node_type IN ('AGENT','DIALOGUE') AND target_id IS NOT NULL)
        OR (node_type IN ('MANUAL','TRIGGER') AND target_id IS NULL));

CREATE TABLE mcp_trigger_configurations (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES mcp_connections(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    UNIQUE (connection_id,id)
);
CREATE INDEX idx_mcp_trigger_configurations_connection ON mcp_trigger_configurations(connection_id);

CREATE TABLE node_trigger_bindings (
    node_row_id UUID PRIMARY KEY REFERENCES workflow_nodes(row_id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES mcp_connections(id) ON DELETE CASCADE,
    configuration_id UUID,
    FOREIGN KEY (connection_id,configuration_id)
        REFERENCES mcp_trigger_configurations(connection_id,id)
);
