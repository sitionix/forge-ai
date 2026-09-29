package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.mcp.*;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Separate outbound shape: Jackson must serialize credentials to Agent. */
public record McpConnectionOutboundRequest(String displayName, URI endpoint, McpConnection.Transport transport,
        McpConnection.AuthType authType, McpConnection.ProjectAccess projectAccess, Set<String> allowedTools,
        McpConnection.CredentialChange credentialChange, Credential credential,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) McpOAuthConfiguration oauthConfiguration) {
    public record Credential(String bearer,Map<String,String> headers,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String clientSecret){
        @Override public String toString(){return "Credential[redacted]";}
    }
    public static McpConnectionOutboundRequest from(McpConnectionCommand c){
        return new McpConnectionOutboundRequest(c.displayName(),c.endpoint(),c.transport(),c.authType(),c.projectAccess(),
                c.allowedTools(),c.credentialChange(),c.bearer()==null && c.headers()==null && c.clientSecret()==null?null:new Credential(c.bearer(),c.headers(),c.clientSecret()),c.oauthConfiguration());
    }
    @Override public String toString(){return "McpConnectionOutboundRequest[redacted]";}
}
