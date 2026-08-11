package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProviderStatus;
import java.util.List;

public record AgentRuntimeProviderResponse(
        String providerId,
        String displayName,
        AgentRuntimeProviderStatus status,
        String version,
        List<AgentRuntimeModelResponse> models
) {
}
