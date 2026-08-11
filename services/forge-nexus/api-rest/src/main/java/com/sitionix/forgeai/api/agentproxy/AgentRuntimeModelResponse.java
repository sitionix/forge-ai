package com.sitionix.forgeai.api.agentproxy;

import java.util.List;

public record AgentRuntimeModelResponse(
        String modelId,
        String displayName,
        String description,
        List<AgentRuntimeEffortResponse> efforts
) {
}
