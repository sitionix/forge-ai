package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.mcp.McpConnection;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionInboundResponse;
import org.springframework.stereotype.Component;

@Component
public class McpClientMapper {
    public McpConnection toDomain(McpConnectionInboundResponse r){
        try {
            if(r==null || r.projectAccess()==null || r.allowedTools()==null)throw new IllegalArgumentException();
            return new McpConnection(r.id(),r.displayName(),r.endpoint(),McpConnection.Transport.valueOf(r.transport()),
                    McpConnection.AuthType.valueOf(r.authType()),r.enabled(),
                    new McpConnection.ProjectAccess(McpConnection.Scope.valueOf(r.projectAccess().scope()),r.projectAccess().projectIds()),
                    r.allowedTools().stream().map(t -> new McpConnection.AllowedTool(t.name(),t.schemaFingerprint()))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    r.credentialConfigured(),r.createdAt(),r.updatedAt(),r.checkedAt(),r.safeDiagnostic());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid MCP upstream response");
        }
    }
}
