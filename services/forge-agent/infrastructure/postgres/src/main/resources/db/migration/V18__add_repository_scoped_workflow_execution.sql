ALTER TABLE workflow_nodes
    ADD COLUMN scope_mode VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    ADD CONSTRAINT chk_workflow_nodes_scope_mode CHECK (scope_mode IN ('GLOBAL', 'PER_SCOPE'));

ALTER TABLE workflow_run_nodes
    ADD COLUMN scope_mode VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    ADD CONSTRAINT chk_workflow_run_nodes_scope_mode CHECK (scope_mode IN ('GLOBAL', 'PER_SCOPE'));

CREATE TABLE workflow_run_repositories (
    workflow_run_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    repository_ordinal INTEGER NOT NULL,
    CONSTRAINT pk_workflow_run_repositories PRIMARY KEY (workflow_run_id, repository_ordinal),
    CONSTRAINT uq_workflow_run_repositories_run_repository UNIQUE (workflow_run_id, repository_id),
    CONSTRAINT fk_workflow_run_repositories_run FOREIGN KEY (workflow_run_id)
        REFERENCES workflow_runs(id) ON DELETE CASCADE
);

ALTER TABLE node_runs
    DROP CONSTRAINT uk_node_runs_workflow_run_frame_source_node,
    ADD COLUMN repository_id UUID NULL;

DROP INDEX uk_node_runs_activation_frame_input;

CREATE UNIQUE INDEX uk_node_runs_global
    ON node_runs(workflow_run_id, execution_frame_id, source_node_id)
    WHERE repository_id IS NULL;
CREATE UNIQUE INDEX uk_node_runs_repository
    ON node_runs(workflow_run_id, execution_frame_id, source_node_id, repository_id)
    WHERE repository_id IS NOT NULL;
CREATE UNIQUE INDEX uk_node_runs_global_activation
    ON node_runs(workflow_run_id, activation_frame_id, entered_via_input_port_id)
    WHERE activation_frame_id IS NOT NULL AND entered_via_input_port_id IS NOT NULL AND repository_id IS NULL;
CREATE UNIQUE INDEX uk_node_runs_repository_activation
    ON node_runs(workflow_run_id, activation_frame_id, entered_via_input_port_id, repository_id)
    WHERE activation_frame_id IS NOT NULL AND entered_via_input_port_id IS NOT NULL AND repository_id IS NOT NULL;

ALTER TABLE workflow_connection_resolutions
    DROP CONSTRAINT uk_connection_resolutions_source_connection,
    ADD COLUMN target_repository_id UUID NULL;

CREATE UNIQUE INDEX uk_connection_resolutions_global_target
    ON workflow_connection_resolutions(source_node_run_id, source_connection_id)
    WHERE target_repository_id IS NULL;
CREATE UNIQUE INDEX uk_connection_resolutions_repository_target
    ON workflow_connection_resolutions(source_node_run_id, source_connection_id, target_repository_id)
    WHERE target_repository_id IS NOT NULL;

ALTER TABLE workflow_input_activation_resolutions
    DROP CONSTRAINT uk_input_activation_resolutions_frame_port,
    ADD COLUMN repository_id UUID NULL;

CREATE UNIQUE INDEX uk_input_activation_resolutions_global
    ON workflow_input_activation_resolutions(workflow_run_id, activation_frame_id, target_input_port_id)
    WHERE repository_id IS NULL;
CREATE UNIQUE INDEX uk_input_activation_resolutions_repository
    ON workflow_input_activation_resolutions(workflow_run_id, activation_frame_id, target_input_port_id, repository_id)
    WHERE repository_id IS NOT NULL;
