package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRunPortResponse(
        UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String description,
        int order,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.sitionix.forgeai.domain.model.agentproxy.AgentDialogueDisposition dialogueDisposition
) {
    public AgentRunPortResponse(UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        int order) { this(sourcePortId, sourceNodeId, direction, name, null, order, null); }

    public AgentRunPortResponse(UUID sourcePortId, UUID sourceNodeId, String direction,
            String name, int order, com.sitionix.forgeai.domain.model.agentproxy.AgentDialogueDisposition dialogueDisposition) {
        this(sourcePortId, sourceNodeId, direction, name, null, order, dialogueDisposition);
    }
}
