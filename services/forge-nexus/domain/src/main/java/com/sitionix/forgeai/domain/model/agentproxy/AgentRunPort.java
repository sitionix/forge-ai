package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunPort(
        UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        int order,
        com.sitionix.forgeai.domain.model.agentproxy.AgentDialogueDisposition dialogueDisposition
) {
    public AgentRunPort(UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        int order) { this(sourcePortId, sourceNodeId, direction, name, order, null); }

}
