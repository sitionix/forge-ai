WITH RECURSIVE task_input_roots AS (
    SELECT workflow.id AS workflow_id,
           port.node_id
    FROM agent_workflows workflow
    JOIN workflow_node_ports port
        ON port.id = workflow.task_input_port_id
       AND port.workflow_id = workflow.id
       AND port.direction = 'INPUT'
    WHERE workflow.task_input_port_id IS NOT NULL
),
reachable_nodes AS (
    SELECT workflow_id, node_id
    FROM task_input_roots
    UNION
    SELECT reachable.workflow_id, target_port.node_id
    FROM reachable_nodes reachable
    JOIN workflow_node_ports source_port
        ON source_port.workflow_id = reachable.workflow_id
       AND source_port.node_id = reachable.node_id
       AND source_port.direction = 'OUTPUT'
    JOIN workflow_connections connection
        ON connection.source_output_port_id = source_port.id
    JOIN workflow_node_ports target_port
        ON target_port.id = connection.target_input_port_id
       AND target_port.workflow_id = reachable.workflow_id
       AND target_port.direction = 'INPUT'
)
UPDATE agent_workflows workflow
SET task_input_port_id = NULL
WHERE workflow.task_input_port_id IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM workflow_nodes node
      WHERE node.workflow_id = workflow.id
        AND NOT EXISTS (
            SELECT 1
            FROM reachable_nodes reachable
            WHERE reachable.workflow_id = node.workflow_id
              AND reachable.node_id = node.id
        )
  );
