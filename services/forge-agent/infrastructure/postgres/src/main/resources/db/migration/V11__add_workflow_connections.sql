CREATE TABLE workflow_connections (
    id UUID PRIMARY KEY,
    source_output_port_id UUID NOT NULL,
    target_input_port_id UUID NOT NULL,
    CONSTRAINT fk_workflow_connections_source_output_port
        FOREIGN KEY (source_output_port_id) REFERENCES workflow_node_ports(id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_connections_target_input_port
        FOREIGN KEY (target_input_port_id) REFERENCES workflow_node_ports(id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_connections_port_pair
        UNIQUE (source_output_port_id, target_input_port_id)
);

ALTER TABLE workflow_nodes
    DROP COLUMN IF EXISTS depends_on_node_ids;
