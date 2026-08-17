ALTER TABLE agent_workflows
    ADD COLUMN task_output_port_id UUID NULL;

ALTER TABLE workflow_runs
    ADD COLUMN task_output_port_id UUID NULL,
    ADD COLUMN result JSONB NULL,
    ADD COLUMN result_source_node_run_id UUID NULL;
