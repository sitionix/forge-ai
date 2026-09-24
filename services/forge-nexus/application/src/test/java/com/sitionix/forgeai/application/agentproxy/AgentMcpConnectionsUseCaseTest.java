package com.sitionix.forgeai.application.agentproxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.domain.port.ForgeAgentMcpClient;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentMcpConnectionsUseCaseTest {
    private final ForgeAgentMcpClient client=mock(ForgeAgentMcpClient.class);
    private final AgentMcpConnectionsUseCase useCase=new AgentMcpConnectionsUseCase(client);
    @Test void rejectsUnsafeFieldsBeforeCallingAgent(){
        for(var command:List.of(
                command(URI.create("https://mcp.example/path?token=canary"),McpConnection.Transport.STREAMABLE_HTTP,"token",null),
                command(URI.create("https://mcp.example/path"),McpConnection.Transport.STREAMABLE_HTTP,"****",null),
                command(URI.create("https://mcp.example/path"),McpConnection.Transport.STREAMABLE_HTTP,null,Map.of("Host","canary")),
                command(URI.create("https://mcp.example/path"),McpConnection.Transport.STREAMABLE_HTTP,null,Map.of("X-Token","line\r\ncanary")))) {
            assertThrows(IllegalArgumentException.class,()->useCase.create(command));
        }
        assertThrows(IllegalArgumentException.class,()->useCase.create(command(URI.create("https://mcp.example/path"),null,"token",null)));
        verifyNoInteractions(client);
    }
    @Test void selectedEmptyIsExplicitAndAllowsDuplicateNames(){
        var c=new McpConnectionCommand("same",URI.create("https://mcp.example/path"),McpConnection.Transport.STREAMABLE_HTTP,
                McpConnection.AuthType.NONE,new McpConnection.ProjectAccess(McpConnection.Scope.SELECTED,Set.of()),Set.of(),null,null,null);
        useCase.create(c);useCase.create(c);
        verify(client,times(2)).create(c);
        assertTrue(c.projectAccess().projectIds().isEmpty());
    }
    private static McpConnectionCommand command(URI uri,McpConnection.Transport transport,String bearer,Map<String,String> headers){
        return new McpConnectionCommand("name",uri,transport,bearer==null && headers==null?McpConnection.AuthType.NONE:
                headers==null?McpConnection.AuthType.BEARER:McpConnection.AuthType.SECRET_HEADERS,
                new McpConnection.ProjectAccess(McpConnection.Scope.ALL,Set.of()),Set.of(),
                bearer==null && headers==null?null:McpConnection.CredentialChange.REPLACE,bearer,headers);
    }
}
