CREATE TABLE dialogues (
    node_run_id UUID PRIMARY KEY REFERENCES node_runs(id) ON DELETE CASCADE,
    state VARCHAR(32) NOT NULL CHECK (state IN ('INITIALIZING','RUNNING','AWAITING_REPLY','AWAITING_REVIEW','COMPLETED','FAILED','CANCELLED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    summary_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE dialogue_messages (
    id UUID PRIMARY KEY,
    node_run_id UUID NOT NULL REFERENCES dialogues(node_run_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    text TEXT NOT NULL,
    turn_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(node_run_id, sequence),
    UNIQUE(id, node_run_id)
);
CREATE TABLE dialogue_turns (
    id UUID PRIMARY KEY,
    node_run_id UUID NOT NULL REFERENCES dialogues(node_run_id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INITIAL','CHAT','SUMMARY')),
    request_id UUID,
    triggering_message_id UUID,
    input_revision BIGINT NOT NULL CHECK (input_revision >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    result JSONB,
    failure_code VARCHAR(120),
    failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    UNIQUE(id, node_run_id),
    UNIQUE(node_run_id, request_id),
    FOREIGN KEY (triggering_message_id, node_run_id) REFERENCES dialogue_messages(id, node_run_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK ((kind='CHAT') = (triggering_message_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_dialogue_turn_active ON dialogue_turns(node_run_id) WHERE status IN ('QUEUED','RUNNING');
ALTER TABLE dialogue_messages ADD FOREIGN KEY (turn_id, node_run_id) REFERENCES dialogue_turns(id, node_run_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE dialogue_revisions (
    id UUID PRIMARY KEY,
    node_run_id UUID NOT NULL REFERENCES dialogues(node_run_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    turn_id UUID NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INITIAL','CHAT','SUMMARY')),
    input_revision BIGINT NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(node_run_id, revision),
    UNIQUE(id, node_run_id),
    FOREIGN KEY (turn_id, node_run_id) REFERENCES dialogue_turns(id, node_run_id)
);
ALTER TABLE dialogues ADD FOREIGN KEY (summary_revision_id, node_run_id) REFERENCES dialogue_revisions(id, node_run_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE dialogue_commands (
    node_run_id UUID NOT NULL REFERENCES dialogues(node_run_id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CHAT','SUMMARY','COMPLETE')),
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(node_run_id, request_id)
);
CREATE TABLE dialogue_completions (
    node_run_id UUID PRIMARY KEY REFERENCES dialogues(node_run_id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    summary_revision_id UUID NOT NULL,
    output_port_id UUID NOT NULL,
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('ACCEPT','REWORK','DEFER')),
    revision BIGINT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (summary_revision_id, node_run_id) REFERENCES dialogue_revisions(id, node_run_id)
);

-- A session belongs to one invocation, even when the source node repeats in a graph loop.
ALTER TABLE agent_execution_sessions ADD COLUMN dialogue_node_run_id UUID UNIQUE REFERENCES dialogues(node_run_id) ON DELETE CASCADE;
ALTER TABLE agent_execution_sessions ADD CONSTRAINT chk_agent_sessions_dialogue_owner CHECK ((context_mode='DIALOGUE_WITHIN_NODE_RUN') = (dialogue_node_run_id IS NOT NULL));
ALTER TABLE agent_execution_turns DROP CONSTRAINT agent_execution_turns_node_run_id_key;
ALTER TABLE agent_execution_turns ADD COLUMN dialogue_turn_id UUID UNIQUE;
ALTER TABLE agent_execution_turns ADD FOREIGN KEY (dialogue_turn_id,node_run_id) REFERENCES dialogue_turns(id,node_run_id) ON DELETE CASCADE;
CREATE UNIQUE INDEX uq_agent_turns_ordinary_node_run ON agent_execution_turns(node_run_id) WHERE dialogue_turn_id IS NULL;

CREATE FUNCTION enforce_dialogue_execution_owner() RETURNS TRIGGER
SET search_path FROM CURRENT AS $$
DECLARE owner_type TEXT; owner_mode TEXT; owner_run UUID; owner_source UUID; owner_agent UUID; session_row agent_execution_sessions%ROWTYPE;
BEGIN
    SELECT node_type,context_mode,workflow_run_id,source_node_id,source_agent_id
      INTO owner_type,owner_mode,owner_run,owner_source,owner_agent FROM node_runs WHERE id=NEW.node_run_id;
    SELECT * INTO session_row FROM agent_execution_sessions WHERE id=NEW.agent_session_id;
    IF owner_type='DIALOGUE' THEN
        IF NEW.dialogue_turn_id IS NULL OR session_row.dialogue_node_run_id IS DISTINCT FROM NEW.node_run_id
            OR session_row.context_mode<>'DIALOGUE_WITHIN_NODE_RUN'
            OR session_row.workflow_run_id IS DISTINCT FROM owner_run
            OR session_row.source_node_id IS DISTINCT FROM owner_source
            OR session_row.source_agent_id IS DISTINCT FROM owner_agent THEN
            RAISE EXCEPTION 'dialogue execution turn must belong to its invocation session';
        END IF;
    ELSIF NEW.dialogue_turn_id IS NOT NULL OR session_row.dialogue_node_run_id IS NOT NULL OR owner_type<>'AGENT' THEN
        RAISE EXCEPTION 'ordinary execution turn requires an ordinary agent invocation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dialogue_execution_owner BEFORE INSERT OR UPDATE OF node_run_id,agent_session_id,dialogue_turn_id
ON agent_execution_turns FOR EACH ROW EXECUTE FUNCTION enforce_dialogue_execution_owner();

CREATE FUNCTION enforce_dialogue_node() RETURNS TRIGGER SET search_path FROM CURRENT AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM node_runs WHERE id=NEW.node_run_id AND node_type='DIALOGUE' AND context_mode='DIALOGUE_WITHIN_NODE_RUN') THEN
        RAISE EXCEPTION 'dialogue requires a dialogue node invocation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dialogue_node BEFORE INSERT ON dialogues FOR EACH ROW EXECUTE FUNCTION enforce_dialogue_node();

-- Transcript, revisions and acceptance are immutable; cascading run deletion remains supported.
CREATE FUNCTION reject_dialogue_history_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'dialogue history is immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dialogue_messages_immutable BEFORE UPDATE ON dialogue_messages FOR EACH ROW EXECUTE FUNCTION reject_dialogue_history_update();
CREATE TRIGGER trg_dialogue_revisions_immutable BEFORE UPDATE ON dialogue_revisions FOR EACH ROW EXECUTE FUNCTION reject_dialogue_history_update();
CREATE TRIGGER trg_dialogue_completions_immutable BEFORE UPDATE ON dialogue_completions FOR EACH ROW EXECUTE FUNCTION reject_dialogue_history_update();
CREATE TRIGGER trg_dialogue_commands_immutable BEFORE UPDATE ON dialogue_commands FOR EACH ROW EXECUTE FUNCTION reject_dialogue_history_update();

CREATE FUNCTION enforce_dialogue_session_owner() RETURNS TRIGGER SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.dialogue_node_run_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM node_runs n WHERE n.id=NEW.dialogue_node_run_id AND n.node_type='DIALOGUE'
        AND n.workflow_run_id=NEW.workflow_run_id AND n.source_node_id=NEW.source_node_id
        AND n.source_agent_id=NEW.source_agent_id AND n.repository_id IS NOT DISTINCT FROM NEW.repository_id) THEN
        RAISE EXCEPTION 'dialogue session ownership must match its node invocation';
    END IF;
    IF TG_OP='UPDATE' AND NEW.dialogue_node_run_id IS DISTINCT FROM OLD.dialogue_node_run_id THEN
        RAISE EXCEPTION 'dialogue session invocation cannot be reassigned';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dialogue_session_owner BEFORE INSERT OR UPDATE OF dialogue_node_run_id,workflow_run_id,source_node_id,source_agent_id,repository_id
ON agent_execution_sessions FOR EACH ROW EXECUTE FUNCTION enforce_dialogue_session_owner();

CREATE FUNCTION guard_dialogue_history_delete() RETURNS TRIGGER AS $$
BEGIN
    IF pg_trigger_depth()>1 THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'dialogue history can only be deleted with its owning run';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dialogue_messages_delete BEFORE DELETE ON dialogue_messages FOR EACH ROW EXECUTE FUNCTION guard_dialogue_history_delete();
CREATE TRIGGER trg_dialogue_revisions_delete BEFORE DELETE ON dialogue_revisions FOR EACH ROW EXECUTE FUNCTION guard_dialogue_history_delete();
CREATE TRIGGER trg_dialogue_completions_delete BEFORE DELETE ON dialogue_completions FOR EACH ROW EXECUTE FUNCTION guard_dialogue_history_delete();
CREATE TRIGGER trg_dialogue_commands_delete BEFORE DELETE ON dialogue_commands FOR EACH ROW EXECUTE FUNCTION guard_dialogue_history_delete();
