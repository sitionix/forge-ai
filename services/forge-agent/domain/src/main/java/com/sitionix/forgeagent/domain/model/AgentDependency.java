package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record AgentDependency(UUID agentId, UUID dependsOnAgentId) {
}
