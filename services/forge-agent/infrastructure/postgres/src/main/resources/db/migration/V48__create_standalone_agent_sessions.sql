CREATE TABLE standalone_agent_sessions (
 id UUID PRIMARY KEY,
 project_id UUID NOT NULL REFERENCES agent_projects(id) ON DELETE CASCADE,
 create_request_id UUID NOT NULL,
 create_request_hash TEXT NOT NULL,
 definition_snapshot JSONB NOT NULL CHECK(jsonb_typeof(definition_snapshot)='object'),
 repository_ids JSONB NOT NULL CHECK(jsonb_typeof(repository_ids)='array'),
 workspace_fingerprint TEXT NOT NULL,
 provider_id TEXT NOT NULL,
 provider_conversation_id TEXT,
 provider_version TEXT,
 status TEXT NOT NULL CHECK(status IN ('CREATED','READY','RUNNING','AWAITING_USER','COMPLETED','FAILED','CANCELLED','RECOVERY_REQUIRED')),
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
 active_turn_id UUID,
 last_message_sequence BIGINT NOT NULL DEFAULT 0,
 last_event_sequence BIGINT NOT NULL DEFAULT 0,
 failure_code TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_activity_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(project_id,create_request_id),
 UNIQUE(id,project_id),
 UNIQUE(provider_id,provider_conversation_id)
);
CREATE INDEX ix_standalone_project_activity ON standalone_agent_sessions(project_id,last_activity_at DESC,id DESC);
CREATE TABLE standalone_agent_turns (
 id UUID PRIMARY KEY,
 session_id UUID NOT NULL REFERENCES standalone_agent_sessions(id) ON DELETE CASCADE,
 request_id UUID NOT NULL,
 request_text TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('QUEUED','RUNNING','RECOVERING','SUCCEEDED','FAILED','CANCELLED','RECOVERY_REQUIRED')),
 provider_turn_id TEXT,
 lease_owner TEXT,
 lease_token BIGINT NOT NULL DEFAULT 0,
 lease_expires_at TIMESTAMPTZ,
 deadline TIMESTAMPTZ,
 dispatch_started BOOLEAN NOT NULL DEFAULT FALSE,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE,
 recovery_attempted BOOLEAN NOT NULL DEFAULT FALSE,
 failure_code TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 finished_at TIMESTAMPTZ,
 UNIQUE(id,session_id),
 UNIQUE(session_id,request_id)
);
CREATE UNIQUE INDEX uq_standalone_active_turn ON standalone_agent_turns(session_id) WHERE status IN ('QUEUED','RUNNING','RECOVERING','RECOVERY_REQUIRED');
ALTER TABLE standalone_agent_sessions ADD CONSTRAINT fk_standalone_active_turn FOREIGN KEY(active_turn_id,id)
 REFERENCES standalone_agent_turns(id,session_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE standalone_agent_messages (
 id UUID PRIMARY KEY,
 session_id UUID NOT NULL,
 turn_id UUID NOT NULL,
 sequence BIGINT NOT NULL CHECK(sequence>0),
 role TEXT NOT NULL CHECK(role IN ('USER','ASSISTANT')),
 text TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(turn_id,session_id) REFERENCES standalone_agent_turns(id,session_id) ON DELETE CASCADE,
 UNIQUE(session_id,sequence),
 UNIQUE(turn_id,role)
);
CREATE TABLE standalone_agent_events (
 session_id UUID NOT NULL,
 turn_id UUID NOT NULL,
 sequence BIGINT NOT NULL CHECK(sequence>0),
 type TEXT NOT NULL,
 status TEXT,
 phase TEXT,
 provider_event_key TEXT,
 payload TEXT NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(session_id,sequence),
 FOREIGN KEY(turn_id,session_id) REFERENCES standalone_agent_turns(id,session_id) ON DELETE CASCADE,
 UNIQUE(turn_id,provider_event_key)
);
CREATE TABLE standalone_agent_cancel_requests (
 session_id UUID NOT NULL REFERENCES standalone_agent_sessions(id) ON DELETE CASCADE,
 request_id UUID NOT NULL,
 expected_revision BIGINT NOT NULL,
 turn_id UUID,
 result_revision BIGINT NOT NULL,
 PRIMARY KEY(session_id,request_id)
);
CREATE FUNCTION guard_standalone_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.project_id,NEW.definition_snapshot,NEW.repository_ids,NEW.workspace_fingerprint,NEW.provider_id,NEW.create_request_id,NEW.create_request_hash)
   IS DISTINCT FROM ROW(OLD.project_id,OLD.definition_snapshot,OLD.repository_ids,OLD.workspace_fingerprint,OLD.provider_id,OLD.create_request_id,OLD.create_request_hash) THEN
   RAISE EXCEPTION 'Standalone definition and ownership are immutable';
 END IF;
 IF OLD.provider_conversation_id IS NOT NULL AND ROW(NEW.provider_conversation_id,NEW.provider_version) IS DISTINCT FROM ROW(OLD.provider_conversation_id,OLD.provider_version) THEN
   RAISE EXCEPTION 'Standalone conversation identity is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_standalone_snapshot BEFORE UPDATE ON standalone_agent_sessions FOR EACH ROW EXECUTE FUNCTION guard_standalone_snapshot();
