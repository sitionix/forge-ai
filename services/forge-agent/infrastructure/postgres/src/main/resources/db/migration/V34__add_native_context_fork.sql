-- Native fork lineage is separate from Reset retirement; old rows remain unchanged.
ALTER TABLE agent_execution_sessions
    ADD COLUMN context_forked_at TIMESTAMPTZ NULL,
    ADD COLUMN forked_from_session_id UUID NULL,
    ADD COLUMN forked_from_turn_id UUID NULL;
ALTER TABLE agent_execution_sessions DROP CONSTRAINT chk_agent_sessions_status;
ALTER TABLE agent_execution_sessions ADD CONSTRAINT chk_agent_sessions_status
    CHECK (status IN ('WAITING','CREATING','RESUMING','IDLE','FORKING','ACTIVE','FAILED','CLOSED'));
ALTER TABLE agent_execution_turns ADD CONSTRAINT uq_agent_turns_lineage UNIQUE (id, agent_session_id);
ALTER TABLE agent_execution_sessions
    ADD CONSTRAINT fk_agent_sessions_fork_parent FOREIGN KEY (forked_from_session_id) REFERENCES agent_execution_sessions(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_agent_sessions_fork_turn FOREIGN KEY (forked_from_turn_id, forked_from_session_id)
        REFERENCES agent_execution_turns(id, agent_session_id) ON DELETE CASCADE,
    ADD CONSTRAINT chk_agent_sessions_fork_lineage CHECK ((forked_from_session_id IS NULL) = (forked_from_turn_id IS NULL)),
    ADD CONSTRAINT chk_agent_sessions_fork_not_self CHECK (forked_from_session_id IS NULL OR forked_from_session_id <> id),
    ADD CONSTRAINT chk_agent_sessions_retirement CHECK (context_reset_at IS NULL OR context_forked_at IS NULL);

DROP INDEX uq_agent_sessions_reusable_global;
CREATE UNIQUE INDEX uq_agent_sessions_reusable_global
    ON agent_execution_sessions(workflow_run_id, source_node_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NULL AND context_reset_at IS NULL AND context_forked_at IS NULL;
DROP INDEX uq_agent_sessions_reusable_scope;
CREATE UNIQUE INDEX uq_agent_sessions_reusable_scope
    ON agent_execution_sessions(workflow_run_id, source_node_id, repository_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NOT NULL AND context_reset_at IS NULL AND context_forked_at IS NULL;
DROP INDEX uq_agent_sessions_iteration_global;
CREATE UNIQUE INDEX uq_agent_sessions_iteration_global
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, source_node_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION' AND repository_id IS NULL AND context_reset_at IS NULL AND context_forked_at IS NULL;
DROP INDEX uq_agent_sessions_iteration_repository;
CREATE UNIQUE INDEX uq_agent_sessions_iteration_repository
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, source_node_id, repository_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_ITERATION' AND repository_id IS NOT NULL AND context_reset_at IS NULL AND context_forked_at IS NULL;
DROP INDEX uq_agent_sessions_shared_global;
CREATE UNIQUE INDEX uq_agent_sessions_shared_global
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, context_group_key)
    WHERE context_mode = 'SHARED_SESSION_GROUP' AND repository_id IS NULL AND context_reset_at IS NULL AND context_forked_at IS NULL;
DROP INDEX uq_agent_sessions_shared_repository;
CREATE UNIQUE INDEX uq_agent_sessions_shared_repository
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, context_group_key, repository_id)
    WHERE context_mode = 'SHARED_SESSION_GROUP' AND repository_id IS NOT NULL AND context_reset_at IS NULL AND context_forked_at IS NULL;

CREATE INDEX idx_agent_sessions_stale_fork ON agent_execution_sessions(updated_at) WHERE status='FORKING';
