package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.UUID;

public record WorkflowRunGraph(
        UUID workflowRunId,
        List<RunNode> nodes,
        List<RunPort> ports,
        List<RunConnection> connections
) {
}
