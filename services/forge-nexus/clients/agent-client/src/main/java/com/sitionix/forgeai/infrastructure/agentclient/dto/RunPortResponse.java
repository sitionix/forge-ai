package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record RunPortResponse(
        UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        int order,
        com.sitionix.forgeai.domain.model.agentproxy.AgentDialogueDisposition dialogueDisposition
) {
    public RunPortResponse(UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        int order) { this(sourcePortId, sourceNodeId, direction, name, order, null); }

}
