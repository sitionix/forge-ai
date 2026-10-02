package com.sitionix.forgeagent;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.api.security.McpManagementProperties;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
class AgentMcpProtectedConfigurationTest {
    @Test void verifierReceivesOnlyActiveCredentialPaths() {
        var settings=new McpManagementProperties();Path key=Path.of("/key"),db=Path.of("/db"),remote=Path.of("/remote");
        settings.setKeyFile(key);settings.setDatabaseCredentialFile(db);
        var verifier=mock(RuntimeBoundaryVerifier.class);var sut=new AgentMcpProtectedConfiguration();
        sut.mcpProtectedPrerequisites(settings,verifier,false,null,new com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthRegistrationProperties(List.of(),null));verify(verifier).verifyProtectedPaths(List.of(key,db));
        sut.mcpProtectedPrerequisites(settings,verifier,true,remote,new com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthRegistrationProperties(List.of(),null));verify(verifier).verifyProtectedPaths(List.of(key,db,remote));
        assertThatThrownBy(() -> sut.mcpProtectedPrerequisites(settings,verifier,true,null,new com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthRegistrationProperties(List.of(),null))).isInstanceOf(NullPointerException.class);
    }
    @Test void registeredClientSecretsAreIncludedInTheExistingRuntimeIsolationCheck() {
        var settings=new McpManagementProperties();settings.setKeyFile(Path.of("/key"));settings.setDatabaseCredentialFile(Path.of("/db"));
        var verifier=mock(RuntimeBoundaryVerifier.class);var client=new com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthRegistrationProperties.Client(java.net.URI.create("https://auth.example"),"client","client_secret_post",Path.of("/oauth-secret"),java.util.Set.of());
        new AgentMcpProtectedConfiguration().mcpProtectedPrerequisites(settings,verifier,false,null,new com.sitionix.forgeagent.infrastructure.local.mcp.oauth.McpOAuthRegistrationProperties(List.of(client),null));
        verify(verifier).verifyProtectedPaths(List.of(Path.of("/key"),Path.of("/db"),Path.of("/oauth-secret")));
    }
}
