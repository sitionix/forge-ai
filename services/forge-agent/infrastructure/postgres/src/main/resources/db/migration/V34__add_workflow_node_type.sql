ALTER TABLE workflow_nodes
    ADD COLUMN node_type VARCHAR(16) NOT NULL DEFAULT 'AGENT',
    ALTER COLUMN target_id DROP NOT NULL,
    ADD CONSTRAINT chk_workflow_nodes_node_type CHECK (node_type IN ('AGENT', 'MANUAL')),
    ADD CONSTRAINT chk_workflow_nodes_type_target CHECK (
        (node_type = 'AGENT' AND target_id IS NOT NULL)
        OR (node_type = 'MANUAL' AND target_id IS NULL)
    );

ALTER TABLE workflow_run_nodes
    ADD COLUMN node_type VARCHAR(16) NOT NULL DEFAULT 'AGENT',
    ALTER COLUMN source_agent_id DROP NOT NULL,
    ALTER COLUMN agent_name DROP NOT NULL,
    ALTER COLUMN agent_instructions DROP NOT NULL,
    ALTER COLUMN agent_output_schema DROP NOT NULL,
    ALTER COLUMN execution_model_provider_id DROP NOT NULL,
    ALTER COLUMN execution_model_id DROP NOT NULL,
    ADD CONSTRAINT chk_workflow_run_nodes_node_type CHECK (node_type IN ('AGENT', 'MANUAL')),
    ADD CONSTRAINT chk_workflow_run_nodes_type_agent_fields CHECK (
        (node_type = 'AGENT'
            AND source_agent_id IS NOT NULL
            AND agent_name IS NOT NULL
            AND agent_instructions IS NOT NULL
            AND agent_output_schema IS NOT NULL
            AND execution_model_provider_id IS NOT NULL
            AND execution_model_id IS NOT NULL)
        OR (node_type = 'MANUAL'
            AND source_agent_id IS NULL
            AND agent_name IS NULL
            AND agent_instructions IS NULL
            AND agent_output_schema IS NULL
            AND execution_model_provider_id IS NULL
            AND execution_model_id IS NULL
            AND execution_model_effort_id IS NULL)
    );

ALTER TABLE node_runs
    ADD COLUMN node_type VARCHAR(16) NOT NULL DEFAULT 'AGENT',
    ALTER COLUMN source_agent_id DROP NOT NULL,
    ALTER COLUMN agent_name DROP NOT NULL,
    ALTER COLUMN agent_instructions DROP NOT NULL,
    ALTER COLUMN agent_output_schema DROP NOT NULL,
    ADD CONSTRAINT chk_node_runs_node_type CHECK (node_type IN ('AGENT', 'MANUAL')),
    ADD CONSTRAINT chk_node_runs_type_agent_fields CHECK (
        -- Execution model columns were already nullable for historical AGENT runs.
        (node_type = 'AGENT'
            AND source_agent_id IS NOT NULL
            AND agent_name IS NOT NULL
            AND agent_instructions IS NOT NULL
            AND agent_output_schema IS NOT NULL)
        OR (node_type = 'MANUAL'
            AND source_agent_id IS NULL
            AND agent_name IS NULL
            AND agent_instructions IS NULL
            AND agent_output_schema IS NULL
            AND execution_model_provider_id IS NULL
            AND execution_model_id IS NULL
            AND execution_model_effort_id IS NULL
            AND context_tracking_version IS NULL)
    );
