ALTER TABLE workflow_nodes
    ADD COLUMN input_mode VARCHAR(32) NOT NULL DEFAULT 'DEPENDENCIES_ONLY';

ALTER TABLE workflow_nodes
    ADD CONSTRAINT chk_workflow_nodes_input_mode
        CHECK (input_mode IN ('DEPENDENCIES_ONLY', 'TASK_AND_DEPENDENCIES'));

ALTER TABLE node_runs
    ADD COLUMN input_mode VARCHAR(32) NOT NULL DEFAULT 'TASK_AND_DEPENDENCIES';

ALTER TABLE node_runs
    ADD CONSTRAINT chk_node_runs_input_mode
        CHECK (input_mode IN ('DEPENDENCIES_ONLY', 'TASK_AND_DEPENDENCIES'));
