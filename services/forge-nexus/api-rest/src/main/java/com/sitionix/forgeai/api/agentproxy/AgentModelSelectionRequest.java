package com.sitionix.forgeai.api.agentproxy;

public record AgentModelSelectionRequest(
        String providerId,
        String modelId,
        String effortId
) {
}
