package com.sitionix.forgeagent.application.mcp;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Catalog orchestration; external discovery happens before persistence/authorization transactions. */
public class McpConnectService {
    private final McpAuthenticationDiscovery discovery;
    private final McpOAuthClientRegistrationProvider registration;
    private final McpConnectionService connections;
    private final McpOAuthService oauth;
    private final Duration timeout;
    public McpConnectService(McpAuthenticationDiscovery discovery,McpOAuthClientRegistrationProvider registration,McpConnectionService connections,McpOAuthService oauth,Duration timeout){this.discovery=discovery;this.registration=registration;this.connections=connections;this.oauth=oauth;this.timeout=timeout;}
    public McpConnectResult connect(String displayName,URI endpoint,String browserBinding) {
        if(displayName==null || displayName.isBlank() || displayName.length()>255 || browserBinding==null || !browserBinding.matches("[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException("Invalid MCP Connect request");
        McpOAuthConfiguration.validateUri(endpoint);
        long deadline=System.nanoTime()+timeout.toNanos();
        var metadata=discovery.discover(endpoint,deadline);
        if(!metadata.oauthRequired())return new McpConnectResult(connections.create(displayName,endpoint,McpAuthType.NONE,McpProjectAccess.selected(Set.of()),null,null,null),null);
        var client=registration.resolve(metadata,deadline);
        var connection=connections.create(displayName,endpoint,McpAuthType.OAUTH,McpProjectAccess.selected(Set.of()),null,client.configuration(),client.credentials());
        var start=oauth.start(connection.id(),browserBinding);
        return new McpConnectResult(connections.get(connection.id()),start);
    }
}
