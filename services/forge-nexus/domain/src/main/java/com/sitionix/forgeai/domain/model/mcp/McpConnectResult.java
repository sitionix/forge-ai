package com.sitionix.forgeai.domain.model.mcp;
public record McpConnectResult(McpConnection connection,McpOAuthStart authorization) {
    public McpConnectResult {
        if(connection==null || connection.id()==null || connection.endpoint()==null || connection.displayName()==null
                || connection.createdAt()==null || connection.updatedAt()==null || connection.enabled() || connection.credentialConfigured()
                || connection.projectAccess()==null || connection.projectAccess().scope()!=McpConnection.Scope.SELECTED || !connection.projectAccess().projectIds().isEmpty()
                || connection.allowedTools()==null || !connection.allowedTools().isEmpty()
                || !(connection.authType()==McpConnection.AuthType.NONE && authorization==null
                    || connection.authType()==McpConnection.AuthType.OAUTH && connection.oauthConfiguration()!=null && authorization!=null && connection.id().equals(authorization.connectionId())))
            throw new IllegalArgumentException("Invalid MCP Connect result");
    }
    @Override public String toString(){return "McpConnectResult[redacted]";}
}
