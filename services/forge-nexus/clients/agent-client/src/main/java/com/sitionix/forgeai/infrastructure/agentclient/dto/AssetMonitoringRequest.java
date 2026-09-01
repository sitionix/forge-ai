package com.sitionix.forgeai.infrastructure.agentclient.dto;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
public record AssetMonitoringRequest(String name, AgentLogProviderType provider, String target, boolean enabled) {}
