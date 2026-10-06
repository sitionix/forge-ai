package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record AgentRuntimeProvider(
        String providerId,
        String displayName,
        AgentRuntimeProviderStatus status,
        String version,
        List<AgentRuntimeModel> models,
        String authState
) {
    public AgentRuntimeProvider(String providerId, String displayName, AgentRuntimeProviderStatus status,
            String version, List<AgentRuntimeModel> models) {
        this(providerId, displayName, status, version, models, "SIGNED_OUT");
    }
}
