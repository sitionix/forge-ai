CREATE TABLE workflow_node_ports (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    node_id UUID NOT NULL,
    direction VARCHAR(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    port_order INTEGER NOT NULL,
    CONSTRAINT fk_workflow_node_ports_node
        FOREIGN KEY (workflow_id, node_id) REFERENCES workflow_nodes(workflow_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_workflow_node_ports_direction
        CHECK (direction IN ('INPUT', 'OUTPUT')),
    CONSTRAINT chk_workflow_node_ports_name_not_blank
        CHECK (btrim(name) <> ''),
    CONSTRAINT chk_workflow_node_ports_description_not_blank
        CHECK (btrim(description) <> ''),
    CONSTRAINT chk_workflow_node_ports_order_non_negative
        CHECK (port_order >= 0),
    CONSTRAINT uk_workflow_node_ports_direction_name
        UNIQUE (workflow_id, node_id, direction, name),
    CONSTRAINT uk_workflow_node_ports_direction_order
        UNIQUE (workflow_id, node_id, direction, port_order)
);

CREATE INDEX idx_workflow_node_ports_workflow_node
    ON workflow_node_ports(workflow_id, node_id);
