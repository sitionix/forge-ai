package com.sitionix.forgeai.infrastructure.agentclient.dto;

public record AgentModelSelectionDto(
        String providerId,
        String modelId,
        String effortId
) {
}
