package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.domain.exception.McpAgentClientException;
import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionOutboundRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Set;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForgeAgentMcpClientAdapterTest {
    private final ForgeAgentHttpClient http=mock(ForgeAgentHttpClient.class);
    private final ForgeAgentClientCallExecutor executor=mock(ForgeAgentClientCallExecutor.class);
    private final ForgeAgentMcpClientAdapter adapter=new ForgeAgentMcpClientAdapter(http,executor,new McpClientMapper());

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
        when(executor.executeMcp(any())).thenReturn(List.of(response));
        assertThatThrownBy(adapter::list).isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid MCP upstream response");
        verify(executor).executeMcp(any());
    }

    @Test void typedErrorCrossesAdapterWithoutPolicy(){
        var error=new McpAgentClientException(409,"DEPENDENCY_CYCLE","cycle",null);
        when(executor.executeMcp(any())).thenThrow(error);
        assertThatThrownBy(adapter::list).isSameAs(error);
    }
}
