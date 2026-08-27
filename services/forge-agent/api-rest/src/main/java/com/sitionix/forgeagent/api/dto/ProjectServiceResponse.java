package com.sitionix.forgeagent.api.dto;
import com.sitionix.forgeagent.domain.model.*;
import java.time.Instant;
import java.util.UUID;
public record ProjectServiceResponse(UUID id,UUID projectId,String name,UUID repositoryId,
    ServiceRuntimeTarget runtimeTarget,Instant createdAt,Instant updatedAt) {}
