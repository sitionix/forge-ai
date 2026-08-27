package com.sitionix.forgeai.domain.model.agentproxy;
import java.time.Instant; import java.util.UUID;
public record AgentProjectService(UUID id,UUID projectId,String name,UUID repositoryId,AgentServiceRuntimeTarget runtimeTarget,Instant createdAt,Instant updatedAt) {}
