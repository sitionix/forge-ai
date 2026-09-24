package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.security.McpManagementProperties;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** Enabled-mode files and runtime isolation must be proven before MCP management is available. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="forge.mcp.enabled",havingValue="true")
@EnableConfigurationProperties(McpManagementProperties.class)
public class AgentMcpProtectedConfiguration {
    @Bean Object mcpProtectedPrerequisites(McpManagementProperties settings,RuntimeBoundaryVerifier verifier,
            @org.springframework.beans.factory.annotation.Value("${forge.agent.remote-access.management-enabled:false}") boolean remoteAccess,
            @org.springframework.beans.factory.annotation.Value("${forge.agent.remote-access.service-secret-file:#{null}}") Path remoteService) {
        Path key = Objects.requireNonNull(settings.getKeyFile(),"MCP key file required");
        Path service = Objects.requireNonNull(settings.getServiceCredentialFile(),"MCP service file required");
        Path database = Objects.requireNonNull(settings.getDatabaseCredentialFile(),"MCP database file required");
        if (Set.of(key,service,database).size() != 3) throw new IllegalStateException("MCP protected files must be distinct");
        var protectedPaths=new ArrayList<>(List.of(key,service,database));
        if (remoteAccess) protectedPaths.add(Objects.requireNonNull(remoteService,"Remote Access service file required"));
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
            ForgeInstanceIdentityRepository identity,McpCredentialCipher cipher) {
        return new McpConnectionService(repository,projects,identity,cipher);
    }
}
