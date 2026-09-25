package com.sitionix.forgeagent.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The approved, execution-scoped snapshot; it never contains a credential or bearer. */
public record McpRuntimeGrant(
        UUID id, UUID installationId, UUID sessionId, UUID turnId, UUID nodeRunId,
        UUID workflowRunId, UUID projectId, String leaseOwnerId, long leaseToken,
        UUID connectionId, URI endpoint,
        McpAuthType authType, String credentialIdentity, Set<McpAllowedTool> tools,
        Instant deadline) {
    public McpRuntimeGrant {
        Objects.requireNonNull(id);
        Objects.requireNonNull(installationId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(turnId);
        Objects.requireNonNull(nodeRunId);
        Objects.requireNonNull(workflowRunId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(leaseOwnerId);
        Objects.requireNonNull(connectionId);
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(authType);
        Objects.requireNonNull(credentialIdentity);
        tools = Set.copyOf(tools);
        Objects.requireNonNull(deadline);
    }
}
