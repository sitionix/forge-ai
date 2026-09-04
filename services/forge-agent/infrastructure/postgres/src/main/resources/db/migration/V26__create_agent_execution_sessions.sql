CREATE TABLE agent_execution_sessions (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    source_agent_id UUID NOT NULL,
    repository_id UUID NULL,
    provider_id VARCHAR(120) NOT NULL,
    provider_conversation_id VARCHAR(512) NULL,
    provider_version VARCHAR(120) NULL,
    context_mode VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    terminal_outcome VARCHAR(32) NULL,
    active_node_run_id UUID NULL,
    lease_owner_id VARCHAR(240) NULL,
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ NULL,
    failure_code VARCHAR(120) NULL,
    failure_message TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_agent_sessions_run_node FOREIGN KEY (workflow_run_id, source_node_id)
        REFERENCES workflow_run_nodes(workflow_run_id, source_node_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_sessions_repository FOREIGN KEY (workflow_run_id, repository_id)
        REFERENCES workflow_run_repositories(workflow_run_id, repository_id),
    CONSTRAINT chk_agent_sessions_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE')),
    CONSTRAINT chk_agent_sessions_status CHECK (status IN ('WAITING','CREATING','RESUMING','IDLE','ACTIVE','FAILED','CLOSED')),
    CONSTRAINT chk_agent_sessions_outcome CHECK (terminal_outcome IS NULL OR terminal_outcome IN ('SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT chk_agent_sessions_closed CHECK ((status = 'CLOSED') = (terminal_outcome IS NOT NULL AND closed_at IS NOT NULL)),
    CONSTRAINT chk_agent_sessions_closed_at CHECK (status = 'CLOSED' OR closed_at IS NULL),
    CONSTRAINT chk_agent_sessions_active_node CHECK ((status IN ('CREATING','RESUMING','ACTIVE')) = (active_node_run_id IS NOT NULL)),
    CONSTRAINT chk_agent_sessions_owned CHECK ((status IN ('CREATING','RESUMING','ACTIVE')) = (lease_owner_id IS NOT NULL)),
    CONSTRAINT chk_agent_sessions_terminal_unowned CHECK (status NOT IN ('FAILED','CLOSED') OR (lease_owner_id IS NULL AND lease_expires_at IS NULL AND active_node_run_id IS NULL)),
    CONSTRAINT chk_agent_sessions_lease CHECK ((lease_owner_id IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT chk_agent_sessions_provider_not_blank CHECK (btrim(provider_id) <> ''),
    CONSTRAINT chk_agent_sessions_lease_token CHECK (lease_token >= 0)
);

CREATE UNIQUE INDEX uq_agent_sessions_reusable_global
    ON agent_execution_sessions(workflow_run_id, source_node_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NULL;
CREATE UNIQUE INDEX uq_agent_sessions_reusable_scope
    ON agent_execution_sessions(workflow_run_id, source_node_id, repository_id)
    WHERE context_mode = 'REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NOT NULL;
CREATE UNIQUE INDEX uq_agent_sessions_provider_conversation
    ON agent_execution_sessions(provider_id, provider_conversation_id)
    WHERE provider_conversation_id IS NOT NULL;

CREATE TABLE agent_execution_turns (
    id UUID PRIMARY KEY,
    agent_session_id UUID NOT NULL REFERENCES agent_execution_sessions(id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL UNIQUE REFERENCES node_runs(id) ON DELETE CASCADE,
    provider_turn_id VARCHAR(512) NULL,
    sequence INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(120) NULL,
    failure_message TEXT NULL,
    started_at TIMESTAMPTZ NULL,
    finished_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_turns_session_sequence UNIQUE (agent_session_id, sequence),
    CONSTRAINT uq_agent_turns_provider_turn UNIQUE (agent_session_id, provider_turn_id),
    CONSTRAINT chk_agent_turns_sequence CHECK (sequence > 0),
    CONSTRAINT chk_agent_turns_status CHECK (status IN ('QUEUED','STARTING','ACTIVE','SUCCEEDED','FAILED','CANCELLED'))
);

CREATE UNIQUE INDEX uq_agent_turns_active_writer ON agent_execution_turns(agent_session_id)
    WHERE status IN ('STARTING','ACTIVE');

ALTER TABLE agent_execution_sessions ADD CONSTRAINT fk_agent_sessions_active_node_run
    FOREIGN KEY (active_node_run_id) REFERENCES node_runs(id);

CREATE FUNCTION enforce_fresh_agent_session_turn() RETURNS TRIGGER AS $$
DECLARE session_mode VARCHAR(48); turn_count BIGINT;
BEGIN
    SELECT context_mode INTO session_mode FROM agent_execution_sessions WHERE id = NEW.agent_session_id;
    IF session_mode = 'FRESH_EACH_NODE_RUN' THEN
        SELECT count(*) INTO turn_count FROM agent_execution_turns WHERE agent_session_id = NEW.agent_session_id AND id <> NEW.id;
        IF NEW.sequence <> 1 OR turn_count <> 0 THEN
            RAISE EXCEPTION 'fresh agent execution session accepts exactly one sequence-1 turn';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_agent_turn_fresh_single
AFTER INSERT OR UPDATE OF agent_session_id, sequence ON agent_execution_turns
DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION enforce_fresh_agent_session_turn();

CREATE FUNCTION enforce_agent_session_scope() RETURNS TRIGGER AS $$
DECLARE snapshot_scope VARCHAR(32);
BEGIN
    SELECT scope_mode INTO snapshot_scope FROM workflow_run_nodes
      WHERE workflow_run_id=NEW.workflow_run_id AND source_node_id=NEW.source_node_id;
    IF (snapshot_scope='GLOBAL' AND NEW.repository_id IS NOT NULL)
       OR (snapshot_scope='PER_SCOPE' AND NEW.repository_id IS NULL) THEN
        RAISE EXCEPTION 'agent execution session repository does not match snapshotted node scope';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_agent_session_scope BEFORE INSERT OR UPDATE OF workflow_run_id,source_node_id,repository_id
ON agent_execution_sessions FOR EACH ROW EXECUTE FUNCTION enforce_agent_session_scope();

CREATE FUNCTION enforce_agent_session_active_turn() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.active_node_run_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM agent_execution_turns t WHERE t.agent_session_id=NEW.id
          AND t.node_run_id=NEW.active_node_run_id AND t.status IN ('STARTING','ACTIVE')) THEN
        RAISE EXCEPTION 'active node run must identify this session active writer turn';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_agent_session_active_turn AFTER INSERT OR UPDATE OF active_node_run_id,status
ON agent_execution_sessions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_agent_session_active_turn();
