package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgentAssetMonitoringRequest(
        @NotBlank String name,
        @NotNull AgentLogProviderType provider,
        @NotBlank String target,
        boolean enabled) {
}
