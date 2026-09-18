ALTER TABLE node_runs
    DROP CONSTRAINT chk_node_runs_status,
    ADD CONSTRAINT chk_node_runs_status CHECK (
        status IN ('PENDING', 'RUNNING', 'WAITING_FOR_MANUAL', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED')
    ),
    ADD CONSTRAINT chk_node_runs_manual_waiting_type CHECK (
        status <> 'WAITING_FOR_MANUAL' OR node_type = 'MANUAL'
    );
