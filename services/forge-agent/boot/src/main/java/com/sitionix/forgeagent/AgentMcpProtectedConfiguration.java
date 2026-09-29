package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.security.McpManagementProperties;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.application.mcp.McpAvailableService;
import com.sitionix.forgeagent.application.mcp.McpProbeService;
import com.sitionix.forgeagent.application.mcp.McpGatewayService;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** Protected files and runtime isolation must be proven before MCP management is available. */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(McpManagementProperties.class)
public class AgentMcpProtectedConfiguration {
    @Bean Object mcpProtectedPrerequisites(McpManagementProperties settings,RuntimeBoundaryVerifier verifier,
            @org.springframework.beans.factory.annotation.Value("${forge.agent.remote-access.management-enabled:false}") boolean remoteAccess,
            @org.springframework.beans.factory.annotation.Value("${forge.agent.remote-access.service-secret-file:#{null}}") Path remoteService,
            com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthRegistrationProperties clients) {
        Path key = Objects.requireNonNull(settings.getKeyFile(),"MCP key file required");
        Path database = Objects.requireNonNull(settings.getDatabaseCredentialFile(),"MCP database file required");
        if (Set.of(key,database).size() != 2) throw new IllegalStateException("MCP protected files must be distinct");
        var protectedPaths=new ArrayList<>(List.of(key,database));
        if (remoteAccess) protectedPaths.add(Objects.requireNonNull(remoteService,"Remote Access service file required"));
        for(var client:clients.clients())if(client.clientSecretFile()!=null) {
            if(protectedPaths.contains(client.clientSecretFile()))throw new IllegalStateException("OAuth client credential must be distinct from Forge credentials");
            protectedPaths.add(client.clientSecretFile());
        }
        verifier.verifyProtectedPaths(protectedPaths);
        return new Object();
    }
    @Bean @DependsOn("mcpProtectedPrerequisites")
    DataSource mcpDataSource(DataSourceProperties datasource,McpManagementProperties settings) {
        byte[] contents = ProtectedMcpKeySource.readProtected(settings.getDatabaseCredentialFile());
        String password = new String(contents,StandardCharsets.UTF_8).stripTrailing();
        Arrays.fill(contents,(byte)0);
        if (password.isBlank()) throw new IllegalStateException("MCP database credential unavailable");
        return datasource.initializeDataSourceBuilder().type(HikariDataSource.class).password(password).build();
    }
    @Bean @DependsOn("mcpProtectedPrerequisites")
    McpLocalKeySource mcpLocalKeySource(McpManagementProperties settings) {
        var source = new ProtectedMcpKeySource(settings.getKeyFile());
        source.keys();
        return source;
    }
    @Bean McpCredentialCipher mcpCredentialCipher(McpLocalKeySource keys) { return new AesGcmMcpCredentialCipher(keys); }
    @Bean McpConnectionService mcpConnectionService(McpConnectionRepository repository,ProjectRepository projects,
            ForgeInstanceIdentityRepository identity,McpCredentialCipher cipher,McpGatewayService gateway,McpOAuthCredentialCipher oauthCipher,McpOAuthClient oauthClient) {
        return new McpConnectionService(repository,projects,identity,cipher,gateway,oauthCipher,oauthClient);
    }
    @Bean com.sitionix.forgeagent.application.mcp.McpOAuthService mcpOAuthService(McpConnectionRepository connections,
            McpOAuthTransactionRepository transactions, ForgeInstanceIdentityRepository identity, McpOAuthClient client,
            McpCredentialCipher cipher, McpOAuthCredentialCipher oauthCipher, McpGatewayService gateway,
            com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthProperties settings) {
        return new com.sitionix.forgeagent.application.mcp.McpOAuthService(connections,transactions,identity,client,cipher,
                oauthCipher,gateway,settings.callbackUri(),settings.transactionTtl(),java.time.Clock.systemUTC());
    }
    @Bean com.sitionix.forgeagent.application.mcp.McpCredentialService mcpCredentialService(McpConnectionRepository connections,
            ForgeInstanceIdentityRepository identity,McpCredentialCipher rawCipher,McpOAuthCredentialCipher cipher,McpOAuthClient client,
            McpRuntimeGrantRepository grants,McpRuntimeToolView views,java.time.Clock clock) {
        return new com.sitionix.forgeagent.application.mcp.McpCredentialService(connections,identity,rawCipher,cipher,client,grants,views,clock);
    }
    @Bean com.sitionix.forgeagent.application.mcp.McpConnectService mcpConnectService(McpAuthenticationDiscovery discovery,
            McpOAuthClientRegistrationProvider registration,McpConnectionService connections,com.sitionix.forgeagent.application.mcp.McpOAuthService oauth,
            com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthDiscoveryProperties properties) {
        return new com.sitionix.forgeagent.application.mcp.McpConnectService(discovery,registration,connections,oauth,properties.discoveryTimeout());
    }
    @Bean McpAvailableService mcpAvailableService(McpRegistryCatalog catalog) {
        return new McpAvailableService(catalog);
    }
    @Bean McpProbeService mcpProbeService(McpConnectionRepository repository,
            ForgeInstanceIdentityRepository identity, com.sitionix.forgeagent.application.mcp.McpCredentialService credentials, McpRemoteProbe remote,
            McpToolInventoryRepository inventory, McpGatewayService gateway) {
        return new McpProbeService(repository, identity, credentials, remote, inventory, gateway);
    }
}
