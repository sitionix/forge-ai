package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;

public record AgentRuntimeModelResponse(
        String modelId,
        String displayName,
        String description,
        List<AgentRuntimeEffortResponse> efforts
) {
}
