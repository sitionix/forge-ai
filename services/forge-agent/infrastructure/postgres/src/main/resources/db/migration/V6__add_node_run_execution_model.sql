ALTER TABLE node_runs
    ADD COLUMN execution_model_provider_id VARCHAR(120),
    ADD COLUMN execution_model_id VARCHAR(240),
    ADD COLUMN execution_model_effort_id VARCHAR(120);
