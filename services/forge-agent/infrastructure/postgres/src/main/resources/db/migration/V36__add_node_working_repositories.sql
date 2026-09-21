ALTER TABLE workflow_nodes ADD COLUMN include_task_repositories BOOLEAN;
UPDATE workflow_nodes SET include_task_repositories = TRUE;
ALTER TABLE workflow_nodes ALTER COLUMN include_task_repositories SET NOT NULL;

CREATE TABLE workflow_node_workspace_repositories (
    workflow_id UUID NOT NULL,
    node_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    repository_ordinal INTEGER NOT NULL,
    PRIMARY KEY (workflow_id, node_id, repository_ordinal),
    FOREIGN KEY (workflow_id, node_id) REFERENCES workflow_nodes(workflow_id, id) ON DELETE CASCADE
);

CREATE TABLE workflow_run_node_workspace_repositories (
    workflow_run_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    repository_ordinal INTEGER NOT NULL,
    PRIMARY KEY (workflow_run_id, source_node_id, repository_ordinal),
    UNIQUE (workflow_run_id, source_node_id, repository_id),
    FOREIGN KEY (workflow_run_id, source_node_id)
        REFERENCES workflow_run_nodes(workflow_run_id, source_node_id) ON DELETE CASCADE
);

INSERT INTO workflow_run_node_workspace_repositories
    (workflow_run_id, source_node_id, repository_id, repository_ordinal)
SELECT n.workflow_run_id, n.source_node_id, r.repository_id, r.repository_ordinal
FROM workflow_run_nodes n
JOIN workflow_run_repositories r ON r.workflow_run_id = n.workflow_run_id
WHERE n.node_type = 'AGENT' AND n.scope_mode = 'GLOBAL';
