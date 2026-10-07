ALTER TABLE workflow_nodes ADD CONSTRAINT chk_workflow_nodes_dialogue_context CHECK ((node_type='DIALOGUE')=(context_mode='DIALOGUE_WITHIN_NODE_RUN'));
ALTER TABLE workflow_run_nodes ADD CONSTRAINT chk_workflow_run_nodes_dialogue_context CHECK ((node_type='DIALOGUE')=(context_mode='DIALOGUE_WITHIN_NODE_RUN'));
ALTER TABLE node_runs ADD CONSTRAINT chk_node_runs_dialogue_context CHECK ((node_type='DIALOGUE')=(context_mode='DIALOGUE_WITHIN_NODE_RUN')),
    ADD CONSTRAINT chk_node_runs_dialogue_scope CHECK (node_type<>'DIALOGUE' OR repository_id IS NULL);
CREATE UNIQUE INDEX uq_dialogue_initial_turn ON dialogue_turns(node_run_id) WHERE kind='INITIAL';
ALTER TABLE dialogue_turns ADD CONSTRAINT chk_dialogue_turn_request CHECK ((kind='INITIAL')=(request_id IS NULL));
ALTER TABLE dialogue_completions ADD FOREIGN KEY (node_run_id,request_id) REFERENCES dialogue_commands(node_run_id,request_id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION enforce_dialogue_revision_turn() RETURNS TRIGGER SET search_path FROM CURRENT AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM dialogue_turns t WHERE t.id=NEW.turn_id AND t.node_run_id=NEW.node_run_id
        AND t.kind=NEW.kind AND t.input_revision=NEW.input_revision AND t.status='SUCCEEDED')
        OR NEW.revision<=NEW.input_revision THEN
        RAISE EXCEPTION 'dialogue revision must identify its exact successful turn and input revision';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dialogue_revision_turn BEFORE INSERT ON dialogue_revisions FOR EACH ROW EXECUTE FUNCTION enforce_dialogue_revision_turn();

CREATE FUNCTION enforce_dialogue_completion_owner() RETURNS TRIGGER SET search_path FROM CURRENT AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM dialogues d JOIN dialogue_revisions r ON r.id=d.summary_revision_id
        WHERE d.node_run_id=NEW.node_run_id AND d.state='AWAITING_REVIEW' AND r.kind='SUMMARY'
          AND r.id=NEW.summary_revision_id AND r.revision=d.revision AND NEW.revision=d.revision+1) THEN
        RAISE EXCEPTION 'dialogue completion requires the exact current summary';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM node_runs n JOIN workflow_run_ports p
        ON p.workflow_run_id=n.workflow_run_id AND p.source_node_id=n.source_node_id
        WHERE n.id=NEW.node_run_id AND p.source_port_id=NEW.output_port_id AND p.direction='OUTPUT'
          AND p.dialogue_disposition=NEW.disposition) THEN
        RAISE EXCEPTION 'dialogue completion requires an output of its snapshotted node';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dialogue_completion_owner BEFORE INSERT ON dialogue_completions FOR EACH ROW EXECUTE FUNCTION enforce_dialogue_completion_owner();
CREATE TRIGGER trg_dialogues_delete BEFORE DELETE ON dialogues FOR EACH ROW EXECUTE FUNCTION guard_dialogue_history_delete();
CREATE TRIGGER trg_dialogue_turns_delete BEFORE DELETE ON dialogue_turns FOR EACH ROW EXECUTE FUNCTION guard_dialogue_history_delete();
