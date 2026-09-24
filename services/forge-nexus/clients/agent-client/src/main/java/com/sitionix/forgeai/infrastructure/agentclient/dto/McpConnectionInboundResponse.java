package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record McpConnectionInboundResponse(UUID id,String displayName,URI endpoint,String transport,
        String authType,boolean enabled,ProjectAccess projectAccess,Set<AllowedTool> allowedTools,
        boolean credentialConfigured,Instant createdAt,Instant updatedAt,Instant checkedAt,String safeDiagnostic) {
    public record ProjectAccess(String scope,Set<UUID> projectIds){}
    public record AllowedTool(String name,String schemaFingerprint){}
}
