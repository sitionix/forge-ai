ALTER TABLE agent_execution_turns
    ADD COLUMN provider_recovery_state VARCHAR(32) NULL,
    ADD COLUMN provider_recovery_terminal_outcome VARCHAR(32) NULL,
    ADD COLUMN provider_recovery_checked_at TIMESTAMPTZ NULL,
    ADD COLUMN recovery_lease_owner_id VARCHAR(240) NULL,
    ADD COLUMN recovery_lease_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN recovery_lease_token BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_agent_turns_provider_recovery_state CHECK (
        provider_recovery_state IS NULL OR provider_recovery_state IN ('TERMINAL', 'ACTIVE', 'UNKNOWN')
    ),
    ADD CONSTRAINT chk_agent_turns_provider_recovery_terminal_outcome CHECK (
        provider_recovery_terminal_outcome IS NULL OR provider_recovery_terminal_outcome IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
    ),
    ADD CONSTRAINT chk_agent_turns_recovery_lease CHECK (
        (recovery_lease_owner_id IS NULL) = (recovery_lease_expires_at IS NULL)
    ),
    ADD CONSTRAINT chk_agent_turns_recovery_lease_token CHECK (recovery_lease_token >= 0);
