package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.UUID;

public record WorkflowRunGraph(
        UUID workflowRunId,
        UUID taskInputPortId,
        UUID taskOutputPortId,
        List<RunNode> nodes,
        List<RunPort> ports,
        List<RunConnection> connections
) {
    public WorkflowRunGraph(final UUID workflowRunId,
                            final UUID taskInputPortId,
                            final List<RunNode> nodes,
                            final List<RunPort> ports,
                            final List<RunConnection> connections) {
        this(workflowRunId, taskInputPortId, null, nodes, ports, connections);
    }
}
