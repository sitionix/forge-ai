package com.sitionix.forgeagent.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record McpConnection(UUID id, UUID installationId, String displayName, URI endpoint,
                            McpAuthType authType, boolean enabled, McpProjectAccess projectAccess,
                            Set<McpAllowedTool> allowedTools, boolean credentialConfigured,
                            Instant createdAt, Instant updatedAt, Instant checkedAt, String safeDiagnostic) {
    public McpConnection {
        if (id == null || installationId == null || displayName == null || displayName.isBlank()
                || endpoint == null || authType == null || projectAccess == null || allowedTools == null
                || createdAt == null || updatedAt == null || allowedTools.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Invalid MCP connection");
        allowedTools = Set.copyOf(allowedTools);
    }
    public McpTransport transport() { return McpTransport.STREAMABLE_HTTP; }
}
