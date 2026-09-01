package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record ReplaceAgentAssetMonitoringCommand(List<Target> targets) {
    public record Target(AgentLogProviderType provider, String target) {}
}
