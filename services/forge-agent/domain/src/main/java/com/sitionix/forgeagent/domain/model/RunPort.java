package com.sitionix.forgeagent.domain.model;

import java.util.UUID;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition;

public record RunPort(
        UUID workflowRunId,
        UUID sourcePortId,
        UUID sourceNodeId,
        PortDirection direction,
        String name,
        String description,
        int order,
        DialogueOutputDisposition dialogueDisposition
) {
    public RunPort(UUID workflowRunId, UUID sourcePortId, UUID sourceNodeId, PortDirection direction, String name, String description, int order) { this(workflowRunId, sourcePortId, sourceNodeId, direction, name, description, order, null); }
}
