-- Explicit opt-in only: historical node ownership and uniqueness remain unchanged.
ALTER TABLE workflow_nodes DROP CONSTRAINT chk_workflow_nodes_context_mode;
ALTER TABLE workflow_nodes ADD CONSTRAINT chk_workflow_nodes_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP'));
ALTER TABLE workflow_nodes DROP CONSTRAINT chk_workflow_nodes_context_group;
ALTER TABLE workflow_nodes ADD CONSTRAINT chk_workflow_nodes_context_group CHECK ((context_mode IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP') AND context_group_key IS NOT NULL AND context_group_key ~ '[^[:space:]]') OR (context_mode NOT IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP') AND context_group_key IS NULL));
ALTER TABLE workflow_run_nodes DROP CONSTRAINT chk_workflow_run_nodes_context_mode;
ALTER TABLE workflow_run_nodes ADD CONSTRAINT chk_workflow_run_nodes_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP'));
ALTER TABLE workflow_run_nodes DROP CONSTRAINT chk_workflow_run_nodes_context_group;
ALTER TABLE workflow_run_nodes ADD CONSTRAINT chk_workflow_run_nodes_context_group CHECK ((context_mode IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP') AND context_group_key IS NOT NULL AND context_group_key ~ '[^[:space:]]') OR (context_mode NOT IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP') AND context_group_key IS NULL));
ALTER TABLE node_runs DROP CONSTRAINT chk_node_runs_context_mode;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP'));
ALTER TABLE node_runs DROP CONSTRAINT chk_node_runs_context_group;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_group CHECK ((context_mode IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP') AND context_group_key IS NOT NULL AND context_group_key ~ '[^[:space:]]') OR (context_mode NOT IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP') AND context_group_key IS NULL));
ALTER TABLE agent_execution_sessions DROP CONSTRAINT chk_agent_sessions_context_mode;
ALTER TABLE agent_execution_sessions ADD CONSTRAINT chk_agent_sessions_context_mode CHECK (context_mode IN ('FRESH_EACH_NODE_RUN', 'REUSE_WITHIN_WORKFLOW_NODE', 'REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP'));
ALTER TABLE node_runs DROP CONSTRAINT chk_node_runs_context_iteration;
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_context_iteration CHECK ((context_mode IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP')) = (context_iteration_id IS NOT NULL));
ALTER TABLE agent_execution_sessions DROP CONSTRAINT chk_agent_execution_sessions_context_iteration;
ALTER TABLE agent_execution_sessions ADD CONSTRAINT chk_agent_execution_sessions_context_iteration CHECK ((context_mode IN ('REUSE_WITHIN_WORKFLOW_ITERATION', 'SHARED_SESSION_GROUP')) = (context_iteration_id IS NOT NULL));
ALTER TABLE agent_execution_sessions ADD COLUMN context_group_key TEXT;
ALTER TABLE agent_execution_sessions ALTER COLUMN source_node_id DROP NOT NULL;
ALTER TABLE agent_execution_sessions ALTER COLUMN source_agent_id DROP NOT NULL;
ALTER TABLE agent_execution_sessions ADD CONSTRAINT chk_agent_sessions_ownership CHECK (
    (context_mode = 'SHARED_SESSION_GROUP' AND source_node_id IS NULL AND source_agent_id IS NULL
        AND context_group_key IS NOT NULL AND context_group_key ~ '[^[:space:]]')
    OR (context_mode <> 'SHARED_SESSION_GROUP' AND source_node_id IS NOT NULL AND source_agent_id IS NOT NULL
        AND context_group_key IS NULL));
CREATE UNIQUE INDEX uq_agent_sessions_shared_global
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, context_group_key)
    WHERE context_mode = 'SHARED_SESSION_GROUP' AND repository_id IS NULL AND context_reset_at IS NULL;
CREATE UNIQUE INDEX uq_agent_sessions_shared_repository
    ON agent_execution_sessions(workflow_run_id, context_iteration_id, context_group_key, repository_id)
    WHERE context_mode = 'SHARED_SESSION_GROUP' AND repository_id IS NOT NULL AND context_reset_at IS NULL;

-- Group ownership no longer supplies a non-null source node for the composite FK.
ALTER TABLE agent_execution_sessions ADD CONSTRAINT fk_agent_sessions_workflow_run
    FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE;

CREATE OR REPLACE FUNCTION enforce_agent_session_scope() RETURNS TRIGGER
SET search_path FROM CURRENT AS $$
DECLARE snapshot_scope VARCHAR(32);
BEGIN
    IF NEW.context_mode = 'SHARED_SESSION_GROUP' THEN
        SELECT scope_mode INTO snapshot_scope FROM workflow_run_nodes
          WHERE workflow_run_id=NEW.workflow_run_id AND context_group_key=NEW.context_group_key
            AND context_mode='SHARED_SESSION_GROUP' LIMIT 1;
        IF snapshot_scope IS NULL THEN
            RAISE EXCEPTION 'shared agent execution session requires a snapshotted shared group';
        END IF;
        IF EXISTS (SELECT 1 FROM workflow_run_nodes
            WHERE workflow_run_id=NEW.workflow_run_id AND context_group_key=NEW.context_group_key
              AND (context_mode<>'SHARED_SESSION_GROUP' OR scope_mode<>snapshot_scope)) THEN
            RAISE EXCEPTION 'shared agent execution session group has incompatible snapshots';
        END IF;
    ELSE
        SELECT scope_mode INTO snapshot_scope FROM workflow_run_nodes
          WHERE workflow_run_id=NEW.workflow_run_id AND source_node_id=NEW.source_node_id;
    END IF;
    IF (snapshot_scope='GLOBAL' AND NEW.repository_id IS NOT NULL)
       OR (snapshot_scope='PER_SCOPE' AND NEW.repository_id IS NULL) THEN
        RAISE EXCEPTION 'agent execution session repository does not match snapshotted node scope';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER trg_agent_session_scope ON agent_execution_sessions;
CREATE TRIGGER trg_agent_session_scope
BEFORE INSERT OR UPDATE OF workflow_run_id,source_node_id,repository_id,context_mode,context_group_key
ON agent_execution_sessions FOR EACH ROW EXECUTE FUNCTION enforce_agent_session_scope();
