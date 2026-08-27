package com.sitionix.forgeai.api.agentproxy;
import com.sitionix.forgeai.domain.model.agentproxy.AgentServiceRuntimeTarget; import java.time.Instant; import java.util.UUID;
public record AgentProjectServiceResponse(UUID id,UUID projectId,String name,UUID repositoryId,AgentServiceRuntimeTarget runtimeTarget,Instant createdAt,Instant updatedAt) {}
