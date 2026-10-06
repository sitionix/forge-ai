package com.sitionix.forgeproxyit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeai.Application;
import com.sitionix.forgeai.domain.model.mcp.McpConnectCommand;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentMcpClientAdapter;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeproxyit.infra.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
@IntegrationTest(properties={"forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}","forge.ai.infrastructure.agent.connect-timeout=2s","forge.ai.infrastructure.agent.read-timeout=5s","forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}","forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}","logging.level.org.springframework.web=TRACE"})
@ContextConfiguration(classes=Application.class)
@Import(NexusProxyTestManagerImpl.class)
@ExtendWith(OutputCaptureExtension.class)
class NexusMcpCatalogConnectIT {
    static final String ORIGIN="http://127.0.0.1:9099",REQUEST="mcp-catalog-connect-request.json";
    @Autowired NexusProxyTestManager manager;
    @SpyBean ForgeAgentMcpClientAdapter adapter;
    @Test void catalogConnectSetsTransactionCookieAndForwardsExactlyTheGeneratedBinding(CapturedOutput output){
        var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.connectMcp(200,"agent-mcp-connect-oauth-response.json")).createDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.connectMcp(REQUEST,200)).header("Origin",ORIGIN).andExpectPath(result->{
            var header=result.getResponse().getHeader("Set-Cookie");assertThat(header).contains("HttpOnly","SameSite=Lax","Max-Age=600");
            String binding=header.substring(header.indexOf('=')+1,header.indexOf(';'));
            verify(adapter).connect(new McpConnectCommand("Catalog MCP",URI.create("https://mcp.example/mcp")),binding);
            assertThat(result.getRequest().getSession(false)).isNull();assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getContentAsString()).doesNotContain(binding,"clientSecret");
        }).assertDefault();upstream.verify();assertThat(output.getAll()).doesNotContain("state-canary");
    }
    @Test void noAuthConnectIsTypedAndDoesNotCreateAnOAuthCookie(){
        var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.connectMcp(200,"agent-mcp-connect-noauth-response.json")).createDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.connectMcp(REQUEST,200)).header("Origin",ORIGIN).andExpectPath(result->assertThat(result.getResponse().getHeader("Set-Cookie")).isNull()).assertDefault();upstream.verify();
    }
    @Test void localDenialsMakeZeroAgentCallsAndDoNotLogInjectedCredentials(CapturedOutput output){
        clearInvocations(adapter);
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.connectMcp(REQUEST,403)).header("Origin","https://evil.example").assertDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.invalidCatalogConnect("mcp-catalog-connect-unknown-request.json",400)).header("Origin",ORIGIN).assertDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.invalidCatalogConnect("mcp-catalog-connect-invalid-request.json",400)).header("Origin",ORIGIN).assertDefault();
        verifyNoInteractions(adapter);assertThat(output.getAll()).doesNotContain("forged-binding-canary");
    }
    @Test void upstreamSetupRequiredPreservesStatusCodeMessageAndCorrelation(){
        error(409,"agent-mcp-connect-setup-error.json",409,"MCP_OAUTH_SETUP_REQUIRED","Forge provider setup is required.","safe-correlation");
    }
    @Test void upstream500IsPreserved(){error(500,"agent-mcp-error-response.json",500,"MCP_OPERATION_FAILED","MCP management operation failed.",null);}
    @Test void malformedErrorFailsSafelyWithoutLeakingBody(CapturedOutput output){error(409,"agent-mcp-connect-malformed-error.json",502,"UPSTREAM_INVALID_RESPONSE",null,null);assertThat(output.getAll()).doesNotContain("malformed-secret-canary");}
    @Test void unavailableTransportHasSafe503(CapturedOutput output){
        doThrow(new org.springframework.web.client.ResourceAccessException("transport-canary",new java.io.IOException("cause-canary"))).when(adapter).connect(any(),anyString());
        try{manager.mockMvc().ping(NexusAgentMockMvcEndpoints.connectMcp(REQUEST,503)).header("Origin",ORIGIN).andExpectPath(result->assertThat(result.getResponse().getContentAsString()).contains("UPSTREAM_UNAVAILABLE").doesNotContain("canary")).assertDefault();}finally{reset(adapter);}
        assertThat(output.getAll()).doesNotContain("transport-canary","cause-canary");
    }
    void error(int agentStatus,String fixture,int status,String code,String message,String correlation){
        var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.connectMcp(agentStatus,fixture)).createDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.connectMcp(REQUEST,status)).header("Origin",ORIGIN).andExpectPath(result->{
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());assertThat(json.get("code").asText()).isEqualTo(code);
            if(message!=null)assertThat(json.get("message").asText()).isEqualTo(message);if(correlation!=null)assertThat(json.get("correlationId").asText()).isEqualTo(correlation);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("malformed-secret-canary");
        }).assertDefault();upstream.verify();
    }
}
