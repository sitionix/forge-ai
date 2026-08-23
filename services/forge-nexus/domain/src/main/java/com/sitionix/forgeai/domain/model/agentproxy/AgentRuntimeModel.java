package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record AgentRuntimeModel(
        String modelId,
        String displayName,
        String description,
        List<AgentRuntimeEffort> efforts
) {
}
