package com.sitionix.forgeai.infrastructure.agentclient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.infrastructure.agentclient.dto.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class McpConnectClientTest {
    @Test void adapterExecutesThenMapsWithoutRunningTheSuppliersInItsTest(){
        var http=mock(ForgeAgentHttpClient.class);var executor=mock(ForgeAgentClientCallExecutor.class);var mapper=mock(McpClientMapper.class);
        var inbound=new McpConnectInbound(connection(),null);var mapped=mock(McpConnectResult.class);
        when(executor.execute(any())).thenReturn(inbound);when(mapper.toDomain(inbound)).thenReturn(mapped);
        var result=new ForgeAgentMcpClientAdapter(http,executor,mapper).connect(new McpConnectCommand("Name",URI.create("https://mcp.example/mcp")),"binding-canary");
        assertThat(result).isSameAs(mapped);verify(executor).execute(any());verify(mapper).toDomain(inbound);verifyNoInteractions(http);
    }
    @Test void mapperValidatesTheConnectResultAndOptionalAuthorization(){
        var mapper=new McpClientMapper();var inbound=new McpConnectInbound(connection(),null);var domain=mapper.toDomain(inbound);
        assertThat(domain.connection().id()).isEqualTo(inbound.connection().id());assertThat(domain.authorization()).isNull();
        assertThatThrownBy(()->mapper.toDomain((McpConnectInbound)null)).isInstanceOf(IllegalStateException.class).hasNoCause();
        var wrong=new McpOAuthStartInbound(UUID.randomUUID(),UUID.randomUUID(),URI.create("https://provider.example/authorize"));
        assertThatThrownBy(()->mapper.toDomain(new McpConnectInbound(connection(),wrong))).isInstanceOf(IllegalStateException.class).hasNoCause();
    }
    @Test void outboundBindingIsSerializedOnlyForAgentAndToStringIsRedacted() throws Exception {
        var request=new McpConnectOutbound("Name",URI.create("https://mcp.example/mcp"),"binding-canary");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request)).contains("binding-canary");assertThat(request.toString()).doesNotContain("canary");
    }
    private McpConnectionInboundResponse connection(){return new McpConnectionInboundResponse(UUID.randomUUID(),"Name",URI.create("https://mcp.example/mcp"),"STREAMABLE_HTTP","NONE",false,new McpConnectionInboundResponse.ProjectAccess("SELECTED",Set.of()),Set.of(),false,Instant.now(),Instant.now(),null,null,null);}
}
