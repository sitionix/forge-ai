ALTER TABLE node_runs
    ADD COLUMN retry_of_node_run_id UUID NULL REFERENCES node_runs(id),
    ADD CONSTRAINT chk_node_runs_retry_not_self CHECK (
        retry_of_node_run_id IS NULL OR retry_of_node_run_id <> id
    );

DROP INDEX uk_node_runs_global;
DROP INDEX uk_node_runs_repository;
DROP INDEX uk_node_runs_global_activation;
DROP INDEX uk_node_runs_repository_activation;

CREATE UNIQUE INDEX uk_node_runs_global
    ON node_runs(workflow_run_id, execution_frame_id, source_node_id)
    WHERE repository_id IS NULL AND retry_of_node_run_id IS NULL;
CREATE UNIQUE INDEX uk_node_runs_repository
    ON node_runs(workflow_run_id, execution_frame_id, source_node_id, repository_id)
    WHERE repository_id IS NOT NULL AND retry_of_node_run_id IS NULL;
CREATE UNIQUE INDEX uk_node_runs_global_activation
    ON node_runs(workflow_run_id, activation_frame_id, entered_via_input_port_id)
    WHERE activation_frame_id IS NOT NULL AND entered_via_input_port_id IS NOT NULL
      AND repository_id IS NULL AND retry_of_node_run_id IS NULL;
CREATE UNIQUE INDEX uk_node_runs_repository_activation
    ON node_runs(workflow_run_id, activation_frame_id, entered_via_input_port_id, repository_id)
    WHERE activation_frame_id IS NOT NULL AND entered_via_input_port_id IS NOT NULL
      AND repository_id IS NOT NULL AND retry_of_node_run_id IS NULL;

CREATE UNIQUE INDEX uq_node_runs_retry_of
    ON node_runs(retry_of_node_run_id)
    WHERE retry_of_node_run_id IS NOT NULL;
