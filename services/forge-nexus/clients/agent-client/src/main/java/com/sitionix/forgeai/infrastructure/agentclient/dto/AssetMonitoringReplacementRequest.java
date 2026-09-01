package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import java.util.List;

public record AssetMonitoringReplacementRequest(List<Target> targets) {
    public record Target(AgentLogProviderType provider, String target) {}
}
