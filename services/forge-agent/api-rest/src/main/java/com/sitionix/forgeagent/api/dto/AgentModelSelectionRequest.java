package com.sitionix.forgeagent.api.dto;

public record AgentModelSelectionRequest(
        String providerId,
        String modelId,
        String effortId
) {
}
