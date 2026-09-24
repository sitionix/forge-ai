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
        var settings=new McpManagementProperties();Path key=Path.of("/key"),service=Path.of("/service"),db=Path.of("/db"),remote=Path.of("/remote");
        settings.setKeyFile(key);settings.setServiceCredentialFile(service);settings.setDatabaseCredentialFile(db);
        var verifier=mock(RuntimeBoundaryVerifier.class);var sut=new AgentMcpProtectedConfiguration();
        sut.mcpProtectedPrerequisites(settings,verifier,false,null);verify(verifier).verifyProtectedPaths(List.of(key,service,db));
        sut.mcpProtectedPrerequisites(settings,verifier,true,remote);verify(verifier).verifyProtectedPaths(List.of(key,service,db,remote));
        assertThatThrownBy(() -> sut.mcpProtectedPrerequisites(settings,verifier,true,null)).isInstanceOf(NullPointerException.class);
    }
}
