package com.sitionix.forgeai.domain.model.agentproxy;
import java.util.UUID;
public record SaveAgentProjectServiceCommand(String name,UUID repositoryId,AgentServiceRuntimeTarget runtimeTarget) {}
