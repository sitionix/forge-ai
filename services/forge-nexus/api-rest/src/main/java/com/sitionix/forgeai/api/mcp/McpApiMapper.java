package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.model.mcp.*;
import org.springframework.stereotype.Component;

@Component
public class McpApiMapper {
    public McpConnectionCommand toCommand(McpConnectionRequest request){return request.toCommand();}
    public McpConnectionResponse toResponse(McpConnection c){return new McpConnectionResponse(c.id(),c.displayName(),c.endpoint(),
            c.transport(),c.authType(),c.enabled(),c.projectAccess(),c.allowedTools(),c.credentialConfigured(),
            c.createdAt(),c.updatedAt(),c.checkedAt(),c.safeDiagnostic());}
}
