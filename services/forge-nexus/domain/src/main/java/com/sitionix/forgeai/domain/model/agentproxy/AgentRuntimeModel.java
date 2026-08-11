package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record AgentRuntimeModel(
        String modelId,
        String displayName,
        String description,
        List<AgentRuntimeEffort> efforts
) {
    public AgentRuntimeModel {
        efforts = efforts == null ? List.of() : List.copyOf(efforts);
    }
}
