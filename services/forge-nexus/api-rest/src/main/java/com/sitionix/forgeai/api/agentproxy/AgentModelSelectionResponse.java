package com.sitionix.forgeai.api.agentproxy;

public record AgentModelSelectionResponse(
        String providerId,
        String modelId,
        String effortId
) {
}
