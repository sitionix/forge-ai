ALTER TABLE agent_execution_turns
    ADD COLUMN event_capture_status VARCHAR(32) NULL,
    ADD COLUMN next_event_sequence BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_agent_turns_event_capture_status CHECK (
        event_capture_status IS NULL OR event_capture_status IN ('NOT_STARTED','ACTIVE','COMPLETE','DEGRADED')
    ),
    ADD CONSTRAINT chk_agent_turns_next_event_sequence CHECK (next_event_sequence > 0),
    ADD CONSTRAINT uq_agent_turns_event_correlation UNIQUE (id, agent_session_id, node_run_id);

CREATE TABLE agent_execution_events (
    id UUID PRIMARY KEY,
    agent_session_id UUID NOT NULL REFERENCES agent_execution_sessions(id) ON DELETE CASCADE,
    agent_turn_id UUID NOT NULL,
    node_run_id UUID NOT NULL REFERENCES node_runs(id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    type VARCHAR(48) NOT NULL,
    status VARCHAR(32) NULL,
    phase VARCHAR(64) NULL,
    provider_event_key VARCHAR(768) NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_events_turn_correlation
        FOREIGN KEY (agent_turn_id, agent_session_id, node_run_id)
        REFERENCES agent_execution_turns(id, agent_session_id, node_run_id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_events_turn_sequence UNIQUE (agent_turn_id, sequence),
    CONSTRAINT chk_agent_events_sequence CHECK (sequence > 0),
    CONSTRAINT chk_agent_events_type CHECK (type IN (
        'TURN','PLAN','REASONING_SUMMARY','COMMAND','FILE_CHANGE','TOOL_CALL',
        'AGENT_MESSAGE','WARNING','ERROR','TOKEN_USAGE','CONTEXT_COMPACTION'
    )),
    CONSTRAINT chk_agent_events_status CHECK (
        status IS NULL OR status IN ('STARTED','IN_PROGRESS','COMPLETED','SUCCEEDED','FAILED')
    ),
    CONSTRAINT chk_agent_events_provider_key CHECK (
        provider_event_key IS NULL OR btrim(provider_event_key) <> ''
    ),
    CONSTRAINT chk_agent_events_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT chk_agent_events_payload_size CHECK (octet_length(payload::text) <= 131072)
);

CREATE UNIQUE INDEX uq_agent_events_provider_key
    ON agent_execution_events(agent_turn_id, provider_event_key)
    WHERE provider_event_key IS NOT NULL;

CREATE INDEX idx_agent_events_turn_order
    ON agent_execution_events(agent_turn_id, sequence ASC);

CREATE FUNCTION reject_agent_execution_event_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'agent execution events are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_agent_execution_events_immutable
BEFORE UPDATE ON agent_execution_events
FOR EACH ROW EXECUTE FUNCTION reject_agent_execution_event_update();
