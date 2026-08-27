package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.time.Instant; import java.util.UUID;
public record ProjectServiceResponse(UUID id,UUID projectId,String name,UUID repositoryId,ServiceRuntimeTargetDto runtimeTarget,Instant createdAt,Instant updatedAt) {}
