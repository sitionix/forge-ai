CREATE TABLE workflow_runs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    source_workflow_id UUID NOT NULL,
    workflow_name VARCHAR(120) NOT NULL,
    input TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NULL,
    finished_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_workflow_runs_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id),
    CONSTRAINT chk_workflow_runs_workflow_name_not_blank CHECK (btrim(workflow_name) <> ''),
    CONSTRAINT chk_workflow_runs_input_not_blank CHECK (btrim(input) <> ''),
    CONSTRAINT chk_workflow_runs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE node_runs (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    source_agent_id UUID NOT NULL,
    agent_name VARCHAR(120) NOT NULL,
    agent_instructions TEXT NOT NULL,
    agent_output_schema JSONB NOT NULL,
    depends_on_node_run_ids UUID[] NOT NULL,
    position_x DOUBLE PRECISION NOT NULL,
    position_y DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    output JSONB NULL,
    failure_code VARCHAR(120) NULL,
    failure_message TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NULL,
    finished_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_node_runs_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id),
    CONSTRAINT uk_node_runs_workflow_run_source_node
        UNIQUE (workflow_run_id, source_node_id),
    CONSTRAINT chk_node_runs_agent_name_not_blank CHECK (btrim(agent_name) <> ''),
    CONSTRAINT chk_node_runs_agent_instructions_not_blank CHECK (btrim(agent_instructions) <> ''),
    CONSTRAINT chk_node_runs_agent_output_schema_object CHECK (jsonb_typeof(agent_output_schema) = 'object'),
    CONSTRAINT chk_node_runs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED'))
);

CREATE INDEX idx_workflow_runs_source_history
    ON workflow_runs(source_workflow_id, created_at DESC, id DESC);
CREATE INDEX idx_workflow_runs_project_history
    ON workflow_runs(project_id, created_at DESC, id DESC);
CREATE INDEX idx_node_runs_workflow_run_id
    ON node_runs(workflow_run_id);
