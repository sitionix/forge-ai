package com.sitionix.forgeai.domain.model.agentproxy;
public record SaveAgentAssetMonitoringCommand(String name, AgentLogProviderType provider, String target, boolean enabled) {}
