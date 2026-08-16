ALTER TABLE agent_workflows
    ADD COLUMN task_input_port_id UUID NULL;

ALTER TABLE workflow_runs
    ADD COLUMN task_input_port_id UUID NULL;

UPDATE agent_workflows workflow
SET task_input_port_id = candidate.port_id
FROM (
    SELECT workflow_id, (ARRAY_AGG(port_id))[1] AS port_id
    FROM (
        SELECT port.workflow_id, port.id AS port_id
        FROM workflow_node_ports port
        LEFT JOIN workflow_connections connection
            ON connection.target_input_port_id = port.id
        WHERE port.direction = 'INPUT'
          AND connection.id IS NULL
    ) unconnected_inputs
    GROUP BY workflow_id
    HAVING COUNT(*) = 1
) candidate
WHERE workflow.id = candidate.workflow_id;
