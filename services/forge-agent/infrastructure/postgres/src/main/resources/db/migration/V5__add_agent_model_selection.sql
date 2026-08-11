ALTER TABLE agent_definitions
    ADD COLUMN model_provider_id VARCHAR(120),
    ADD COLUMN model_id VARCHAR(240),
    ADD COLUMN model_effort_id VARCHAR(120);
