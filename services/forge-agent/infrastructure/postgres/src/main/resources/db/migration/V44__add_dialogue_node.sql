-- Dialogue definitions and immutable workflow snapshots.
ALTER TABLE workflow_nodes DROP CONSTRAINT chk_workflow_nodes_node_type, ADD CONSTRAINT chk_workflow_nodes_node_type CHECK (node_type IN ('AGENT','MANUAL','DIALOGUE')), DROP CONSTRAINT chk_workflow_nodes_type_target, ADD CONSTRAINT chk_workflow_nodes_type_target CHECK (
        (node_type IN ('AGENT', 'DIALOGUE') AND target_id IS NOT NULL)
        OR (node_type = 'MANUAL' AND target_id IS NULL)
);
ALTER TABLE workflow_run_nodes DROP CONSTRAINT chk_workflow_run_nodes_node_type, ADD CONSTRAINT chk_workflow_run_nodes_node_type CHECK (node_type IN ('AGENT','MANUAL','DIALOGUE')), DROP CONSTRAINT chk_workflow_run_nodes_type_agent_fields, ADD CONSTRAINT chk_workflow_run_nodes_type_agent_fields CHECK (
        (node_type IN ('AGENT', 'DIALOGUE')
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
ALTER TABLE node_runs DROP CONSTRAINT chk_node_runs_node_type, ADD CONSTRAINT chk_node_runs_node_type CHECK (node_type IN ('AGENT','MANUAL','DIALOGUE')), DROP CONSTRAINT chk_node_runs_type_agent_fields, ADD CONSTRAINT chk_node_runs_type_agent_fields CHECK (
        -- Execution model columns were already nullable for historical AGENT runs.
        (node_type IN ('AGENT', 'DIALOGUE')
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
ALTER TABLE workflow_nodes DROP CONSTRAINT chk_workflow_nodes_context_mode, ADD CONSTRAINT chk_workflow_nodes_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN','REUSE_WITHIN_WORKFLOW_NODE','REUSE_WITHIN_WORKFLOW_ITERATION','SHARED_SESSION_GROUP','DIALOGUE_WITHIN_NODE_RUN'));
ALTER TABLE workflow_run_nodes DROP CONSTRAINT chk_workflow_run_nodes_context_mode, ADD CONSTRAINT chk_workflow_run_nodes_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN','REUSE_WITHIN_WORKFLOW_NODE','REUSE_WITHIN_WORKFLOW_ITERATION','SHARED_SESSION_GROUP','DIALOGUE_WITHIN_NODE_RUN'));
ALTER TABLE node_runs DROP CONSTRAINT chk_node_runs_context_mode, ADD CONSTRAINT chk_node_runs_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN','REUSE_WITHIN_WORKFLOW_NODE','REUSE_WITHIN_WORKFLOW_ITERATION','SHARED_SESSION_GROUP','DIALOGUE_WITHIN_NODE_RUN'));
ALTER TABLE agent_execution_sessions DROP CONSTRAINT chk_agent_sessions_context_mode, ADD CONSTRAINT chk_agent_sessions_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN','REUSE_WITHIN_WORKFLOW_NODE','REUSE_WITHIN_WORKFLOW_ITERATION','SHARED_SESSION_GROUP','DIALOGUE_WITHIN_NODE_RUN'));
ALTER TABLE workflow_nodes ADD CONSTRAINT chk_workflow_nodes_dialogue_scope CHECK (node_type <> 'DIALOGUE' OR scope_mode = 'GLOBAL');
ALTER TABLE workflow_run_nodes ADD CONSTRAINT chk_workflow_run_nodes_dialogue_scope CHECK (node_type <> 'DIALOGUE' OR scope_mode = 'GLOBAL');
ALTER TABLE workflow_node_ports ADD COLUMN dialogue_disposition VARCHAR(16), ADD CONSTRAINT chk_workflow_node_ports_dialogue_disposition CHECK (dialogue_disposition IS NULL OR (direction='OUTPUT' AND dialogue_disposition IN ('ACCEPT','REWORK','DEFER')));
ALTER TABLE workflow_run_ports ADD COLUMN dialogue_disposition VARCHAR(16), ADD CONSTRAINT chk_workflow_run_ports_dialogue_disposition CHECK (dialogue_disposition IS NULL OR (direction='OUTPUT' AND dialogue_disposition IN ('ACCEPT','REWORK','DEFER')));
ALTER TABLE node_runs DROP CONSTRAINT chk_node_runs_status, ADD CONSTRAINT chk_node_runs_status CHECK (status IN ('PENDING','RUNNING','WAITING_FOR_MANUAL','WAITING_FOR_DIALOGUE','SUCCEEDED','FAILED','BLOCKED','CANCELLED')), ADD CONSTRAINT chk_node_runs_dialogue_waiting CHECK (status <> 'WAITING_FOR_DIALOGUE' OR node_type='DIALOGUE');
