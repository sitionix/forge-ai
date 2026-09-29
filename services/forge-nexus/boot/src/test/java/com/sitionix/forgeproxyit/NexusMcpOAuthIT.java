package com.sitionix.forgeproxyit;

import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeai.Application;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentMcpClientAdapter;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.*;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import com.sitionix.forgeproxyit.infra.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

@IntegrationTest(properties={"forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.agent.connect-timeout=5s","forge.ai.infrastructure.agent.read-timeout=5s",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}","forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}",
        "logging.level.org.springframework.web=TRACE"})
@ContextConfiguration(classes=Application.class)
@Import(NexusProxyTestManagerImpl.class)
@ExtendWith(OutputCaptureExtension.class)
class NexusMcpOAuthIT {
    private static final UUID ID=UUID.fromString("77777777-7777-4777-8777-777777777777"),TX=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String ORIGIN="http://127.0.0.1:9099",COOKIE="ForgeMcpOAuth-"+TX;
    @Autowired NexusProxyTestManager manager;
    @SpyBean ForgeAgentMcpClientAdapter adapter;
    @Test void wrongOriginAndMissingBrowserCookieMakeZeroAgentCalls() {
        clearInvocations(adapter);
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.startMcpOAuth(403)).withPathParameters(PathParams.create().add("id",ID))
                .header("Origin","http://evil.example").assertDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.cancelMcpOAuth(403)).withPathParameters(PathParams.create().add("id",ID).add("transactionId",TX))
                .header("Origin",ORIGIN).assertDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.mcpOAuthCallback()).withQueryParameters(QueryParams.create().add("state",TX+".state-canary").add("code","code-canary"))
                .andExpectPath(result->{assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/fgaisox/operator/mcp-oauth-result.html?transactionId="+TX+"&result=failed");
                    assertThat(result.getResponse().getContentAsString()).isEmpty();}).assertDefault();
        verifyNoInteractions(adapter);
    }
    @Test void typedStartCookieCallbackAndCancelWorkWithoutOperatorLoginAndNeverLogSecrets(CapturedOutput output) {
        var start=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.startMcpOAuth())
                .pathPattern(WireMockPathParams.create().add("id",equalTo(ID.toString()))).createDefault();
        var completion=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.completeMcpOAuth()).createDefault();
        Map<String,String> saved=new HashMap<>();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.startMcpOAuth(200)).withPathParameters(PathParams.create().add("id",ID)).header("Origin",ORIGIN)
                .andExpectPath(result->{var header=result.getResponse().getHeader("Set-Cookie");
                    assertThat(header).contains("HttpOnly","SameSite=Lax","Max-Age=600");
                    saved.put("binding",header.substring(header.indexOf('=')+1,header.indexOf(';')));
                    assertThat(result.getRequest().getSession(false)).isNull();}).assertDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.mcpOAuthCallback()).cookie(COOKIE,saved.get("binding"))
                .withQueryParameters(QueryParams.create().add("state",TX+".state-canary").add("code","code-canary").add("iss","https://provider.example"))
                .andExpectPath(result->{assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/fgaisox/operator/mcp-oauth-result.html?transactionId="+TX+"&connectionId="+ID+"&result=connected");
                    assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
                    assertThat(result.getResponse().getContentAsString()).isEmpty();}).assertDefault();
        var cancel=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.cancelMcpOAuth())
                .pathPattern(WireMockPathParams.create().add("id",equalTo(ID.toString())).add("transactionId",equalTo(TX.toString()))).createDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.cancelMcpOAuth(204)).withPathParameters(PathParams.create().add("id",ID).add("transactionId",TX))
                .header("Origin",ORIGIN).cookie(COOKIE,saved.get("binding")).assertDefault();
        start.verify();completion.verify();cancel.verify();
        assertThat(output.getAll()).doesNotContain("state-canary","code-canary",saved.get("binding"));
    }
}
