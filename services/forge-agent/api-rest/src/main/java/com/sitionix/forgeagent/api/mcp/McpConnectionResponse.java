package com.sitionix.forgeagent.api.mcp;

import com.sitionix.forgeagent.domain.model.McpConnection;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record McpConnectionResponse(UUID id, String displayName, URI endpoint, McpConnectionRequest.Transport transport,
                                    String authType, boolean enabled, McpConnectionRequest.ProjectAccess projectAccess,
                                    Set<McpAllowedTool> allowedTools, boolean credentialConfigured, Instant createdAt,
                                    Instant updatedAt, Instant checkedAt, String safeDiagnostic) {
    public static McpConnectionResponse from(McpConnection value) {
        return new McpConnectionResponse(value.id(),value.displayName(),value.endpoint(),
                McpConnectionRequest.Transport.valueOf(value.transport().name()),value.authType().name(),value.enabled(),
                new McpConnectionRequest.ProjectAccess(
                        McpConnectionRequest.ProjectAccess.Scope.valueOf(value.projectAccess().scope().name()),
                        value.projectAccess().projectIds()),value.allowedTools(),value.credentialConfigured(),
                value.createdAt(),value.updatedAt(),value.checkedAt(),value.safeDiagnostic());
    }
}
