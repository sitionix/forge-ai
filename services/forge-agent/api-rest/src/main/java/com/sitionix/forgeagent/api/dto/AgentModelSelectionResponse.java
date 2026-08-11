package com.sitionix.forgeagent.api.dto;

public record AgentModelSelectionResponse(
        String providerId,
        String modelId,
        String effortId
) {
}
