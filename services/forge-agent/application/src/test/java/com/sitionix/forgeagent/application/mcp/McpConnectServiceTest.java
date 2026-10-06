package com.sitionix.forgeagent.application.mcp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
class McpConnectServiceTest {
    final McpAuthenticationDiscovery discovery=mock(McpAuthenticationDiscovery.class);
    final McpOAuthClientRegistrationProvider registration=mock(McpOAuthClientRegistrationProvider.class);
    final McpConnectionService connections=mock(McpConnectionService.class);
    final McpOAuthService oauth=mock(McpOAuthService.class);
    final URI endpoint=URI.create("https://mcp.example/mcp"),issuer=URI.create("https://auth.example");
    final String binding="synthetic-browser-binding-32-characters";
    final McpConnectService service=new McpConnectService(discovery,registration,connections,oauth,Duration.ofSeconds(20));
    @Test void connectCreatesDisabledOAuthThenStartsExistingTransaction(){
        var config=config();var setup=new McpOAuthCredentials("client-canary",null);var registered=new McpOAuthClientRegistration(config,setup);var connection=connection(McpAuthType.OAUTH,config);
        when(discovery.discover(eq(endpoint),anyLong())).thenReturn(metadata());when(registration.resolve(any(),anyLong())).thenReturn(registered);
        when(connections.create("GitHub",endpoint,McpAuthType.OAUTH,McpProjectAccess.selected(Set.of()),null,config,setup)).thenReturn(connection);
        var start=new McpOAuthStart(UUID.randomUUID(),connection.id(),issuer.resolve("/authorize"));when(oauth.start(connection.id(),binding)).thenReturn(start);when(connections.get(connection.id())).thenReturn(connection);
        var result=service.connect("GitHub",endpoint,binding);assertThat(result.connection().enabled()).isFalse();assertThat(result.authorization()).isEqualTo(start);
        var order=inOrder(discovery,registration,connections,oauth);order.verify(discovery).discover(eq(endpoint),anyLong());order.verify(registration).resolve(eq(metadata()),anyLong());
        order.verify(connections).create(anyString(),eq(endpoint),eq(McpAuthType.OAUTH),eq(McpProjectAccess.selected(Set.of())),isNull(),eq(config),eq(setup));order.verify(oauth).start(connection.id(),binding);
        var deadlines=org.mockito.ArgumentCaptor.forClass(Long.class);verify(discovery).discover(eq(endpoint),deadlines.capture());verify(registration).resolve(any(),deadlines.capture());assertThat(deadlines.getAllValues().get(0)).isEqualTo(deadlines.getAllValues().get(1));
        verify(connections,never()).setEnabled(any(),anyBoolean());
    }
    @Test void noAuthConnectCreatesDisabledConnection(){when(discovery.discover(eq(endpoint),anyLong())).thenReturn(McpAuthenticationMetadata.noAuth(endpoint));
        var c=connection(McpAuthType.NONE,null);when(connections.create("Public",endpoint,McpAuthType.NONE,McpProjectAccess.selected(Set.of()),null,null,null)).thenReturn(c);
        var result=service.connect("Public",endpoint,binding);assertThat(result.authorization()).isNull();assertThat(result.connection()).isEqualTo(c);verifyNoInteractions(registration,oauth);}
    @Test void missingRegistrationMakesZeroConnectionWrites(){when(discovery.discover(eq(endpoint),anyLong())).thenReturn(metadata());when(registration.resolve(any(),anyLong())).thenThrow(McpOAuthException.setupRequired());
        assertThatThrownBy(()->service.connect("Name",endpoint,binding)).isInstanceOf(McpOAuthException.class);verifyNoInteractions(connections,oauth);}
    @Test void templateAndInvalidInputMakeZeroCalls(){for(String name:List.of("","x".repeat(256)))assertThatThrownBy(()->service.connect(name,endpoint,binding)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.connect("Name",URI.create("javascript:alert(1)"),binding)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.connect("Name",endpoint,"short")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(discovery,registration,connections,oauth);}
    @Test void discoveryFailureMakesZeroMutations(){when(discovery.discover(any(),anyLong())).thenThrow(McpOAuthException.unavailable());assertThatThrownBy(()->service.connect("Name",endpoint,binding)).isInstanceOf(McpOAuthException.class);verifyNoInteractions(connections,oauth);}
    @Test void startFailureKeepsCreatedConnectionDisabled(){var config=config();var c=connection(McpAuthType.OAUTH,config);when(discovery.discover(any(),anyLong())).thenReturn(metadata());when(registration.resolve(any(),anyLong())).thenReturn(new McpOAuthClientRegistration(config,new McpOAuthCredentials(null,null)));
        when(connections.create(anyString(),any(),any(),any(),any(),any(),any())).thenReturn(c);when(oauth.start(c.id(),binding)).thenThrow(McpOAuthException.unavailable());assertThatThrownBy(()->service.connect("Name",endpoint,binding)).isInstanceOf(McpOAuthException.class);verify(connections,never()).setEnabled(any(),anyBoolean());assertThat(c.enabled()).isFalse();}
    McpAuthenticationMetadata metadata(){return new McpAuthenticationMetadata(true,endpoint,issuer,issuer.resolve("/authorize"),issuer.resolve("/token"),null,null,false,Set.of("read"),Set.of("none"),Set.of("S256"));}
    McpOAuthConfiguration config(){return new McpOAuthConfiguration(issuer,issuer.resolve("/authorize"),issuer.resolve("/token"),null,"client","none",Set.of("read"),endpoint);}
    McpConnection connection(McpAuthType type,McpOAuthConfiguration config){return new McpConnection(UUID.randomUUID(),UUID.randomUUID(),"Name",endpoint,type,false,McpProjectAccess.selected(Set.of()),Set.of(),false,Instant.now(),Instant.now(),null,null,config,null);}
}
