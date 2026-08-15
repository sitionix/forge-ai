ALTER TABLE node_runs
    DROP CONSTRAINT IF EXISTS uk_node_runs_workflow_run_source_node;

ALTER TABLE node_runs
    DROP COLUMN IF EXISTS depends_on_node_run_ids;

ALTER TABLE node_runs
    ADD COLUMN execution_frame_id UUID,
    ADD COLUMN entered_via_input_port_id UUID NULL,
    ADD COLUMN activation_frame_id UUID NULL,
    ADD COLUMN selected_output_port_id UUID NULL;

CREATE TABLE workflow_execution_frames (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL,
    parent_frame_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_execution_frames_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_execution_frames_parent
        FOREIGN KEY (parent_frame_id) REFERENCES workflow_execution_frames(id) ON DELETE CASCADE
);

CREATE TABLE workflow_run_nodes (
    workflow_run_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    source_agent_id UUID NOT NULL,
    agent_name VARCHAR(120) NOT NULL,
    agent_instructions TEXT NOT NULL,
    agent_output_schema JSONB NOT NULL,
    execution_model_provider_id VARCHAR(120) NOT NULL,
    execution_model_id VARCHAR(240) NOT NULL,
    execution_model_effort_id VARCHAR(120) NULL,
    input_mode VARCHAR(32) NOT NULL,
    position_x DOUBLE PRECISION NOT NULL,
    position_y DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (workflow_run_id, source_node_id),
    CONSTRAINT fk_workflow_run_nodes_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT chk_workflow_run_nodes_agent_name_not_blank CHECK (btrim(agent_name) <> ''),
    CONSTRAINT chk_workflow_run_nodes_agent_instructions_not_blank CHECK (btrim(agent_instructions) <> ''),
    CONSTRAINT chk_workflow_run_nodes_agent_output_schema_object CHECK (jsonb_typeof(agent_output_schema) = 'object'),
    CONSTRAINT chk_workflow_run_nodes_input_mode
        CHECK (input_mode IN ('DEPENDENCIES_ONLY', 'TASK_AND_DEPENDENCIES'))
);

CREATE TABLE workflow_run_ports (
    workflow_run_id UUID NOT NULL,
    source_port_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    direction VARCHAR(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    port_order INTEGER NOT NULL,
    PRIMARY KEY (workflow_run_id, source_port_id),
    CONSTRAINT fk_workflow_run_ports_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT chk_workflow_run_ports_direction
        CHECK (direction IN ('INPUT', 'OUTPUT')),
    CONSTRAINT chk_workflow_run_ports_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_workflow_run_ports_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT chk_workflow_run_ports_order_non_negative CHECK (port_order >= 0)
);

CREATE TABLE workflow_run_connections (
    workflow_run_id UUID NOT NULL,
    source_connection_id UUID NOT NULL,
    source_output_port_id UUID NOT NULL,
    target_input_port_id UUID NOT NULL,
    PRIMARY KEY (workflow_run_id, source_connection_id),
    CONSTRAINT fk_workflow_run_connections_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE
);

CREATE TABLE workflow_connection_resolutions (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL,
    execution_frame_id UUID NOT NULL,
    source_node_run_id UUID NOT NULL,
    source_connection_id UUID NOT NULL,
    target_input_port_id UUID NOT NULL,
    resolution_type VARCHAR(32) NOT NULL,
    payload JSONB NULL,
    consumed_by_node_run_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_connection_resolutions_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_connection_resolutions_frame
        FOREIGN KEY (execution_frame_id) REFERENCES workflow_execution_frames(id) ON DELETE CASCADE,
    CONSTRAINT fk_connection_resolutions_source_node_run
        FOREIGN KEY (source_node_run_id) REFERENCES node_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_connection_resolutions_consumed_by
        FOREIGN KEY (consumed_by_node_run_id) REFERENCES node_runs(id) ON DELETE SET NULL,
    CONSTRAINT chk_connection_resolutions_type
        CHECK (resolution_type IN ('DELIVERED', 'CLOSED')),
    CONSTRAINT uk_connection_resolutions_source_connection
        UNIQUE (source_node_run_id, source_connection_id)
);

CREATE TABLE workflow_input_activation_resolutions (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL,
    activation_frame_id UUID NOT NULL,
    target_input_port_id UUID NOT NULL,
    activated_node_run_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_input_activation_resolutions_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_input_activation_resolutions_frame
        FOREIGN KEY (activation_frame_id) REFERENCES workflow_execution_frames(id) ON DELETE CASCADE,
    CONSTRAINT fk_input_activation_resolutions_node_run
        FOREIGN KEY (activated_node_run_id) REFERENCES node_runs(id) ON DELETE SET NULL,
    CONSTRAINT uk_input_activation_resolutions_frame_port
        UNIQUE (workflow_run_id, activation_frame_id, target_input_port_id)
);

ALTER TABLE node_runs
    ADD CONSTRAINT fk_node_runs_execution_frame
        FOREIGN KEY (execution_frame_id) REFERENCES workflow_execution_frames(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_node_runs_activation_frame
        FOREIGN KEY (activation_frame_id) REFERENCES workflow_execution_frames(id) ON DELETE CASCADE,
    ADD CONSTRAINT uk_node_runs_workflow_run_frame_source_node
        UNIQUE (workflow_run_id, execution_frame_id, source_node_id);

CREATE UNIQUE INDEX uk_node_runs_activation_frame_input
    ON node_runs(workflow_run_id, activation_frame_id, entered_via_input_port_id)
    WHERE activation_frame_id IS NOT NULL AND entered_via_input_port_id IS NOT NULL;

CREATE INDEX idx_execution_frames_workflow_run
    ON workflow_execution_frames(workflow_run_id);
CREATE INDEX idx_workflow_run_ports_workflow_node
    ON workflow_run_ports(workflow_run_id, source_node_id);
CREATE INDEX idx_workflow_run_connections_source_output
    ON workflow_run_connections(workflow_run_id, source_output_port_id);
CREATE INDEX idx_workflow_run_connections_target_input
    ON workflow_run_connections(workflow_run_id, target_input_port_id);
CREATE INDEX idx_connection_resolutions_frame
    ON workflow_connection_resolutions(workflow_run_id, execution_frame_id);
