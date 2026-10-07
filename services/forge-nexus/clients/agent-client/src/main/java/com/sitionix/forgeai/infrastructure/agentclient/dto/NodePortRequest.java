package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record NodePortRequest(
        UUID id,
        String name,
        String description,
        int order,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.sitionix.forgeai.domain.model.agentproxy.AgentDialogueDisposition dialogueDisposition
) {
    public NodePortRequest(UUID id,
        String name,
        String description,
        int order) { this(id, name, description, order, null); }

}
