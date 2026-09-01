package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AgentAssetMonitoringReplacementRequest(@NotNull List<@Valid Target> targets) {
    public record Target(@NotNull AgentLogProviderType provider, @NotBlank String target) {}
}
