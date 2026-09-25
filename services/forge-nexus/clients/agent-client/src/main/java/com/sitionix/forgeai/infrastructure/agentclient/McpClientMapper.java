package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.mcp.McpConnection;
import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import com.sitionix.forgeai.domain.model.mcp.McpAvailableServer;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpAvailablePageInbound;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionInboundResponse;
import org.springframework.stereotype.Component;

@Component
public class McpClientMapper {
    public McpAvailablePage toDomain(McpAvailablePageInbound response) {
        try {
            if (response == null || response.servers() == null) {
                throw new IllegalArgumentException();
            }
            return new McpAvailablePage(response.servers().stream()
                    .map(this::toDomain)
                    .toList(), response.nextCursor());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid MCP upstream response");
        }
    }

    private McpAvailableServer toDomain(McpAvailablePageInbound.McpAvailableServerInbound server) {
        if (server == null || server.name() == null || server.name().isBlank()
                || server.endpoint() == null || server.endpoint().isBlank()) {
            throw new IllegalArgumentException();
        }
        return new McpAvailableServer(server.name(), server.title(), server.description(),
                server.version(), server.endpoint());
    }

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
