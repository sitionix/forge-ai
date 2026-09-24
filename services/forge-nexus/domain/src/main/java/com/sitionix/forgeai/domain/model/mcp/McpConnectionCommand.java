package com.sitionix.forgeai.domain.model.mcp;

import java.net.URI;
import java.util.Map;
import java.util.Set;

public final class McpConnectionCommand {
    private final String displayName;
    private final URI endpoint;
    private final McpConnection.Transport transport;
    private final McpConnection.AuthType authType;
    private final McpConnection.ProjectAccess projectAccess;
    private final Set<String> allowedTools;
    private final McpConnection.CredentialChange credentialChange;
    private final String bearer;
    private final Map<String,String> headers;
    public McpConnectionCommand(String displayName,URI endpoint,McpConnection.Transport transport,McpConnection.AuthType authType,
            McpConnection.ProjectAccess projectAccess,Set<String> allowedTools,McpConnection.CredentialChange credentialChange,
            String bearer,Map<String,String> headers) {
        this.displayName=displayName;this.endpoint=endpoint;this.transport=transport;this.authType=authType;
        this.projectAccess=projectAccess;this.allowedTools=allowedTools;this.credentialChange=credentialChange;
        this.bearer=bearer;this.headers=headers;
    }
    public String displayName(){return displayName;}
    public URI endpoint(){return endpoint;}
    public McpConnection.Transport transport(){return transport;}
    public McpConnection.AuthType authType(){return authType;}
    public McpConnection.ProjectAccess projectAccess(){return projectAccess;}
    public Set<String> allowedTools(){return allowedTools;}
    public McpConnection.CredentialChange credentialChange(){return credentialChange;}
    public String bearer(){return bearer;}
    public Map<String,String> headers(){return headers;}
    @Override public String toString(){return "McpConnectionCommand[redacted]";}
}
