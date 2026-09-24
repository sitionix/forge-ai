package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.model.mcp.McpConnection;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record McpConnectionResponse(UUID id,String displayName,URI endpoint,McpConnection.Transport transport,
        McpConnection.AuthType authType,boolean enabled,McpConnection.ProjectAccess projectAccess,
        Set<McpConnection.AllowedTool> allowedTools,boolean credentialConfigured,Instant createdAt,Instant updatedAt,
        Instant checkedAt,String safeDiagnostic) {}
