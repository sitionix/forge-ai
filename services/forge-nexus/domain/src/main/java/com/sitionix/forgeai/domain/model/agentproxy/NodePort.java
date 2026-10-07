package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record NodePort(
        UUID id,
        String name,
        String description,
        int order,
        com.sitionix.forgeai.domain.model.agentproxy.AgentDialogueDisposition dialogueDisposition
) {
    public NodePort(UUID id,
        String name,
        String description,
        int order) { this(id, name, description, order, null); }

}
