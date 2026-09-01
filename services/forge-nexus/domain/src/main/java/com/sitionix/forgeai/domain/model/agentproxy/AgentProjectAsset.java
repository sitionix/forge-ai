package com.sitionix.forgeai.domain.model.agentproxy;
import java.time.Instant; import java.util.UUID;
public record AgentProjectAsset(UUID id, UUID projectId, String name, UUID sshConnectionId, Instant createdAt, Instant updatedAt) {}
