DROP TABLE IF EXISTS agent_dependencies;

CREATE TABLE agent_workflows (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_agent_workflows_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id),
    CONSTRAINT uk_agent_workflows_project_normalized_name
        UNIQUE (project_id, normalized_name),
    CONSTRAINT chk_agent_workflows_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_agent_workflows_normalized_name_not_blank CHECK (btrim(normalized_name) <> '')
);

CREATE TABLE workflow_nodes (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    target_id UUID NOT NULL,
    depends_on_node_ids UUID[] NOT NULL,
    position_x DOUBLE PRECISION NOT NULL,
    position_y DOUBLE PRECISION NOT NULL,
    CONSTRAINT fk_workflow_nodes_workflow
        FOREIGN KEY (workflow_id) REFERENCES agent_workflows(id),
    CONSTRAINT fk_workflow_nodes_target
        FOREIGN KEY (target_id) REFERENCES agent_definitions(id)
);

CREATE INDEX idx_agent_workflows_project_normalized_name_id
    ON agent_workflows(project_id, normalized_name, id);
CREATE INDEX idx_workflow_nodes_workflow_id
    ON workflow_nodes(workflow_id);
CREATE INDEX idx_workflow_nodes_target_id
    ON workflow_nodes(target_id);
