ALTER TABLE agent_execution_sessions ADD COLUMN context_reset_at TIMESTAMPTZ NULL;

DROP INDEX uq_agent_sessions_reusable_global;
DROP INDEX uq_agent_sessions_reusable_scope;

CREATE UNIQUE INDEX uq_agent_sessions_reusable_global
    ON agent_execution_sessions(workflow_run_id, source_node_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NULL AND context_reset_at IS NULL;
CREATE UNIQUE INDEX uq_agent_sessions_reusable_scope
    ON agent_execution_sessions(workflow_run_id, source_node_id, repository_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NOT NULL AND context_reset_at IS NULL;
