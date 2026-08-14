package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import java.util.List;

public record SaveWorkflowCommand(
        String name,
        List<Node> nodes,
        List<WorkflowConnection> connections
) {
}
