package com.sitionix.forgeagent.domain.model;
public record McpConnectResult(McpConnection connection,McpOAuthStart authorization) {
    public McpConnectResult {
        if(connection==null || connection.enabled() || authorization!=null && (!connection.id().equals(authorization.connectionId()) || connection.authType()!=McpAuthType.OAUTH))
            throw new IllegalArgumentException("Invalid MCP Connect result");
    }
}
