package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.Node;
import java.util.List;

public record SaveWorkflowCommand(
        String name,
        List<Node> nodes
) {
}
