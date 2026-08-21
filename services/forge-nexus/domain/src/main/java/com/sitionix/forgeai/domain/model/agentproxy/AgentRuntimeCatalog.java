package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record AgentRuntimeCatalog(
        List<AgentRuntimeProvider> providers
) {
    public AgentRuntimeCatalog {
        providers = List.copyOf(providers);
    }
}
