ALTER TABLE agent_workflows
    ADD COLUMN task_input_port_id UUID NULL;

ALTER TABLE workflow_runs
    ADD COLUMN task_input_port_id UUID NULL;

UPDATE agent_workflows workflow
SET task_input_port_id = candidate.port_id
FROM (
    WITH unconnected_input_roots AS (
        SELECT port.workflow_id, port.id AS port_id
        FROM workflow_node_ports port
        LEFT JOIN workflow_connections connection
            ON connection.target_input_port_id = port.id
        WHERE port.direction = 'INPUT'
          AND connection.id IS NULL
    ),
    zero_input_roots AS (
        SELECT node.workflow_id, node.id AS node_id
        FROM workflow_nodes node
        LEFT JOIN workflow_node_ports port
            ON port.workflow_id = node.workflow_id
           AND port.node_id = node.id
           AND port.direction = 'INPUT'
        WHERE port.id IS NULL
    ),
    implicit_roots AS (
        SELECT workflow_id, port_id AS root_id
        FROM unconnected_input_roots
        UNION ALL
        SELECT workflow_id, node_id AS root_id
        FROM zero_input_roots
    ),
    root_counts AS (
        SELECT workflow_id, COUNT(*) AS root_count
        FROM implicit_roots
        GROUP BY workflow_id
    )
    SELECT unconnected_input_roots.workflow_id, unconnected_input_roots.port_id
    FROM unconnected_input_roots
    JOIN root_counts
        ON root_counts.workflow_id = unconnected_input_roots.workflow_id
    WHERE root_counts.root_count = 1
) candidate
WHERE workflow.id = candidate.workflow_id;
