ALTER TABLE workflow_runs
    ADD COLUMN operator_stop_status VARCHAR(16),
    ADD COLUMN operator_stop_failure_code VARCHAR(64),
    ADD COLUMN operator_stop_pending_node_run_ids JSONB,
    ADD COLUMN operator_stop_attempt BIGINT NOT NULL DEFAULT 0;

ALTER TABLE workflow_runs
    ADD CONSTRAINT chk_workflow_runs_operator_stop_status
        CHECK (operator_stop_status IS NULL OR operator_stop_status IN ('PENDING', 'COMPLETE', 'FAILED')),
    ADD CONSTRAINT chk_workflow_runs_operator_stop_pending_ids
        CHECK (
            operator_stop_pending_node_run_ids IS NULL
            OR jsonb_typeof(operator_stop_pending_node_run_ids) = 'array'
        ),
    ADD CONSTRAINT chk_workflow_runs_operator_stop_consistency
        CHECK (
            (operator_stop_status IS NULL
                AND operator_stop_failure_code IS NULL
                AND operator_stop_pending_node_run_ids IS NULL
                AND operator_stop_attempt = 0)
            OR
            (operator_stop_status = 'COMPLETE'
                AND operator_stop_failure_code IS NULL
                AND operator_stop_pending_node_run_ids = '[]'::jsonb
                AND operator_stop_attempt > 0)
            OR
            (operator_stop_status = 'PENDING'
                AND operator_stop_failure_code IS NULL
                AND operator_stop_pending_node_run_ids IS NOT NULL
                AND operator_stop_attempt > 0)
            OR
            (operator_stop_status = 'FAILED'
                AND operator_stop_failure_code = 'AGENT_EXECUTION_INTERRUPT_FAILED'
                AND operator_stop_pending_node_run_ids IS NOT NULL
                AND jsonb_array_length(operator_stop_pending_node_run_ids) > 0
                AND operator_stop_attempt > 0)
        );
