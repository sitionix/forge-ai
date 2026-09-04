ALTER TABLE workflow_nodes ADD COLUMN context_mode VARCHAR(48);
UPDATE workflow_nodes SET context_mode = 'FRESH_EACH_NODE_RUN' WHERE context_mode IS NULL;
ALTER TABLE workflow_nodes ALTER COLUMN context_mode SET NOT NULL;
ALTER TABLE workflow_nodes ADD CONSTRAINT chk_workflow_nodes_context_mode
    CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE'));

ALTER TABLE workflow_run_nodes ADD COLUMN context_mode VARCHAR(48);
UPDATE workflow_run_nodes SET context_mode = 'FRESH_EACH_NODE_RUN' WHERE context_mode IS NULL;
ALTER TABLE workflow_run_nodes ALTER COLUMN context_mode SET NOT NULL;
ALTER TABLE workflow_run_nodes ADD CONSTRAINT chk_workflow_run_nodes_context_mode
    CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE'));

ALTER TABLE node_runs ADD COLUMN context_mode VARCHAR(48);
UPDATE node_runs SET context_mode = 'FRESH_EACH_NODE_RUN' WHERE context_mode IS NULL;
ALTER TABLE node_runs ALTER COLUMN context_mode SET NOT NULL;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_mode
    CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE'));
ALTER TABLE node_runs ADD COLUMN context_tracking_version INTEGER;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_tracking_version
    CHECK (context_tracking_version IS NULL OR context_tracking_version = 1);
