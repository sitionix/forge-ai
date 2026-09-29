package com.sitionix.forgeai.api.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sitionix.forgeai.domain.model.mcp.*;
import java.net.URI;
import java.util.Map;
import java.util.Set;

public record McpConnectionRequest(String displayName, URI endpoint, McpConnection.Transport transport,
        McpConnection.AuthType authType, McpConnection.ProjectAccess projectAccess, Set<String> allowedTools,
        McpConnection.CredentialChange credentialChange, @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) Credential credential,McpOAuthConfiguration oauthConfiguration) {
    public static final class Credential {
        private final String clientSecret;
        private final String bearer;
        private final Map<String,String> headers;
        public Credential(@JsonProperty("bearer") String bearer,@JsonProperty("headers") Map<String,String> headers,@JsonProperty("clientSecret") String clientSecret){this.clientSecret=clientSecret;this.bearer=bearer;this.headers=headers;}
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String bearer(){return bearer;}
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public Map<String,String> headers(){return headers;}
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String clientSecret(){return clientSecret;}
        @Override public String toString(){return "Credential[redacted]";}
    }
    public McpConnectionCommand toCommand(){
        if ((credentialChange==McpConnection.CredentialChange.REPLACE)!=(credential!=null)
                || (credential!=null && credential.bearer()==null && credential.headers()==null && credential.clientSecret()==null))
            throw new IllegalArgumentException("Invalid MCP request");
        return new McpConnectionCommand(displayName,endpoint,transport,authType,projectAccess,
                allowedTools,credentialChange,credential==null?null:credential.bearer(),credential==null?null:credential.headers(),oauthConfiguration,credential==null?null:credential.clientSecret());
    }
    @Override public String toString(){return "McpConnectionRequest[redacted]";}
}
