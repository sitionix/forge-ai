package com.sitionix.forgeai.api.agentproxy;

import java.util.List;

public record AgentRuntimeResponse(
        List<AgentRuntimeProviderResponse> providers
) {
}
