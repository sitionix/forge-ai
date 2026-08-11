package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record AgentRuntimeProvider(
        String providerId,
        String displayName,
        AgentRuntimeProviderStatus status,
        String version,
        List<AgentRuntimeModel> models
) {
    public AgentRuntimeProvider {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
