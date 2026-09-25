package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.domain.exception.AgentClientException;
import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionOutboundRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpProbeInboundResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ForgeAgentMcpClientAdapterTest {
    private final ForgeAgentHttpClient http=mock(ForgeAgentHttpClient.class);
    private final ForgeAgentClientCallExecutor executor=mock(ForgeAgentClientCallExecutor.class);
    private final ForgeAgentMcpClientAdapter adapter=new ForgeAgentMcpClientAdapter(http,executor,new McpClientMapper());

    @Test void testConnectionUsesExecutorAndMapperWithoutLocalErrorRouting() {
        var mapper = mock(McpClientMapper.class);
        var tested = new ForgeAgentMcpClientAdapter(http, executor, mapper);
        UUID id = UUID.randomUUID();
        var inbound = new McpProbeInboundResponse("2025-11-25", List.of());
        var mapped = new McpProbeReport("2025-11-25", List.of());
        when(executor.execute(any())).thenReturn(inbound);
        when(mapper.toDomain(inbound)).thenReturn(mapped);
        assertThat(tested.test(id)).isSameAs(mapped);
        verify(executor).execute(any());
        verify(mapper).toDomain(inbound);
        verifyNoInteractions(http);
    }

    @Test void outboundBearerAndHeadersSerializeForAgentButNeverEnterToString() throws Exception {
        for (var input : List.of(new McpConnectionCommand("x",URI.create("https://mcp.example/path"),
                McpConnection.Transport.STREAMABLE_HTTP,McpConnection.AuthType.BEARER,
                new McpConnection.ProjectAccess(McpConnection.Scope.ALL,Set.of()),Set.of(),
                McpConnection.CredentialChange.REPLACE,"bearer-canary",null),
                new McpConnectionCommand("x",URI.create("https://mcp.example/path"),
                McpConnection.Transport.STREAMABLE_HTTP,McpConnection.AuthType.SECRET_HEADERS,
                new McpConnection.ProjectAccess(McpConnection.Scope.ALL,Set.of()),Set.of(),
                McpConnection.CredentialChange.REPLACE,null,Map.of("X-Secret","header-canary")))) {
            var request=McpConnectionOutboundRequest.from(input);
            assertThat(new ObjectMapper().writeValueAsString(request))
                    .contains(input.bearer()==null?"header-canary":"bearer-canary");
            assertThat(input.toString()+request.toString()+request.credential().toString())
                    .doesNotContain("bearer-canary","header-canary");
        }
    }

    @Test void mapsResponseAfterTransportExecution(){
        var response=mock(com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionInboundResponse.class);
        when(executor.execute(any())).thenReturn(List.of(response));
        assertThatThrownBy(adapter::list).isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid MCP upstream response");
        verify(executor).execute(any());
    }

    @Test void createMapsRequestExecutesHttpAndThenMapsResponse(){
        var mapper=mock(McpClientMapper.class);
        var tested=new ForgeAgentMcpClientAdapter(http,executor,mapper);
        var response=mock(com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionInboundResponse.class);
        var domain=mock(McpConnection.class);
        when(http.createMcpConnection(any())).thenReturn(response);
        when(mapper.toDomain(response)).thenReturn(domain);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get()).when(executor).execute(any());
        var command=new McpConnectionCommand("fixture",URI.create("https://mcp.example/path"),
                McpConnection.Transport.STREAMABLE_HTTP,McpConnection.AuthType.BEARER,
                new McpConnection.ProjectAccess(McpConnection.Scope.ALL,Set.of()),Set.of(),
                McpConnection.CredentialChange.REPLACE,"bearer-canary",null);

        assertThat(tested.create(command)).isSameAs(domain);

        var order=inOrder(executor,http,mapper);
        order.verify(executor).execute(any());
        var request=org.mockito.ArgumentCaptor.forClass(McpConnectionOutboundRequest.class);
        order.verify(http).createMcpConnection(request.capture());
        order.verify(mapper).toDomain(response);
        assertThat(request.getValue().displayName()).isEqualTo("fixture");
        assertThat(request.getValue().credential().bearer()).isEqualTo("bearer-canary");
    }

    @Test void typedErrorCrossesAdapterWithoutPolicy(){
        var error=new AgentClientException(409,"{\"code\":\"DEPENDENCY_CYCLE\",\"message\":\"cycle\"}",Map.of(),null);
        when(executor.execute(any())).thenThrow(error);
        assertThatThrownBy(adapter::list).isSameAs(error);
    }
}
