ALTER TABLE workflow_nodes
    DROP CONSTRAINT workflow_nodes_pkey;

ALTER TABLE workflow_nodes
    ADD CONSTRAINT pk_workflow_nodes PRIMARY KEY (workflow_id, id);
