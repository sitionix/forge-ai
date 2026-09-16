-- Historical modes keep NULL identities. No synthetic iteration backfill.
ALTER TABLE workflow_nodes DROP CONSTRAINT chk_workflow_nodes_context_mode;
ALTER TABLE workflow_nodes ADD CONSTRAINT chk_workflow_nodes_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION'));
ALTER TABLE workflow_nodes ADD COLUMN context_group_key TEXT;
ALTER TABLE workflow_nodes ADD CONSTRAINT chk_workflow_nodes_context_group CHECK ((context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION' AND context_group_key IS NOT NULL AND context_group_key ~ '[^[:space:]]') OR (context_mode <> 'REUSE_WITHIN_WORKFLOW_ITERATION' AND context_group_key IS NULL));

ALTER TABLE workflow_run_nodes DROP CONSTRAINT chk_workflow_run_nodes_context_mode;
ALTER TABLE workflow_run_nodes ADD CONSTRAINT chk_workflow_run_nodes_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION'));
ALTER TABLE workflow_run_nodes ADD COLUMN context_group_key TEXT;
ALTER TABLE workflow_run_nodes ADD CONSTRAINT chk_workflow_run_nodes_context_group CHECK ((context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION' AND context_group_key IS NOT NULL AND context_group_key ~ '[^[:space:]]') OR (context_mode <> 'REUSE_WITHIN_WORKFLOW_ITERATION' AND context_group_key IS NULL));

ALTER TABLE node_runs DROP CONSTRAINT chk_node_runs_context_mode;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION'));
ALTER TABLE node_runs ADD COLUMN context_group_key TEXT;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_group CHECK ((context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION' AND context_group_key IS NOT NULL AND context_group_key ~ '[^[:space:]]') OR (context_mode <> 'REUSE_WITHIN_WORKFLOW_ITERATION' AND context_group_key IS NULL));
ALTER TABLE node_runs ADD COLUMN context_iteration_id UUID;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_iteration CHECK ((context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION') = (context_iteration_id IS NOT NULL));

ALTER TABLE agent_execution_sessions DROP CONSTRAINT chk_agent_sessions_context_mode;
ALTER TABLE agent_execution_sessions ADD CONSTRAINT chk_agent_sessions_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION'));
ALTER TABLE agent_execution_sessions ADD COLUMN context_iteration_id UUID;
ALTER TABLE agent_execution_sessions ADD CONSTRAINT chk_agent_execution_sessions_context_iteration CHECK ((context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION') = (context_iteration_id IS NOT NULL));

CREATE UNIQUE INDEX uq_agent_sessions_iteration_global
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, source_node_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION' AND repository_id IS NULL AND context_reset_at IS NULL;
CREATE UNIQUE INDEX uq_agent_sessions_iteration_repository
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, source_node_id, repository_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION' AND repository_id IS NOT NULL AND context_reset_at IS NULL;
