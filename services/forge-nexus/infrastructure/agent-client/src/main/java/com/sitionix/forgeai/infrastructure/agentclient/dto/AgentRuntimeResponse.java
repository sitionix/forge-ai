package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;

public record AgentRuntimeResponse(
        List<AgentRuntimeProviderResponse> providers
) {
}
