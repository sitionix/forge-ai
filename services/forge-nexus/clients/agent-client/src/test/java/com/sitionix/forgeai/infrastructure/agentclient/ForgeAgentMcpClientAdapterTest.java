package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.domain.exception.AgentClientException;
import com.sitionix.forgeai.domain.exception.McpAgentClientException;
import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionOutboundRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

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

    @Test void rawHttpBodyHeadersAndCauseNeverLeaveMcpPort(){
        when(executor.execute(any())).thenThrow(new AgentClientException(500,"body-canary",
                Map.of("X-Secret",List.of("header-canary")),new IllegalStateException("cause-canary")));
        assertThatThrownBy(adapter::list).satisfies(this::safeGraph);
    }

    @Test void knownHttpStatusesKeepOnlySafeCategories(){
        for(var entry:Map.of(400,McpAgentClientException.Category.INVALID_REQUEST,
                404,McpAgentClientException.Category.NOT_FOUND,
                500,McpAgentClientException.Category.UPSTREAM_ERROR).entrySet()){
            reset(executor);
            when(executor.execute(any())).thenThrow(new AgentClientException(entry.getKey(),"body-canary",
                    Map.of("X-Secret",List.of("header-canary")),new IllegalStateException("cause-canary")));
            assertThatThrownBy(adapter::list).isInstanceOf(McpAgentClientException.class)
                .satisfies(error -> {
                    assertThat(((McpAgentClientException)error).category()).isEqualTo(entry.getValue());
                    safeGraph(error);
                });
        }
        reset(executor);
        when(executor.execute(any())).thenThrow(new ResourceAccessException("transport-canary"));
        assertThatThrownBy(adapter::list).isInstanceOf(McpAgentClientException.class)
            .satisfies(error -> assertThat(((McpAgentClientException)error).category())
                .isEqualTo(McpAgentClientException.Category.UPSTREAM_UNAVAILABLE));
    }

    @Test void transportAndDecodeCausesNeverLeaveMcpPort(){
        for (RuntimeException raw : List.of(new ResourceAccessException("transport-canary"),
                new RestClientException("decode-canary"))) {
            reset(executor);
            when(executor.execute(any())).thenThrow(raw);
            assertThatThrownBy(adapter::list).satisfies(this::safeGraph);
        }
    }

    private void safeGraph(Throwable error){
        assertThat(error.getCause()).isNull();
        assertThat(error.getSuppressed()).isEmpty();
        String stack=java.util.Arrays.toString(error.getStackTrace());
        assertThat(error.toString()+stack).doesNotContain("body-canary","header-canary","cause-canary",
                "transport-canary","decode-canary");
    }
}
