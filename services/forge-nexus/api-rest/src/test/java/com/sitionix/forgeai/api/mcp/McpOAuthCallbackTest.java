package com.sitionix.forgeai.api.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.domain.usecase.ManageAgentMcpConnections;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpOAuthCallbackTest {
    private final ManageAgentMcpConnections service=mock(ManageAgentMcpConnections.class);
    private final ForgeAiMcpOAuthController controller=new ForgeAiMcpOAuthController(service,
            new McpOAuthBrowserProperties(URI.create("http://127.0.0.1:9099"),Duration.ofMinutes(10)),new McpApiMapper());
    @Test void startSetsScopedHttpOnlyCookieWithoutOperatorSession() {
        UUID id=UUID.randomUUID(),tx=UUID.randomUUID();
        when(service.startOAuth(eq(id),anyString())).thenReturn(new McpOAuthStart(tx,id,URI.create("https://provider.example/authorize")));
        var request=browser();var response=new MockHttpServletResponse();
        assertThat(controller.start(id,Map.of(),request,response).transactionId()).isEqualTo(tx);
        assertThat(response.getHeader("Set-Cookie")).contains("ForgeMcpOAuth-"+tx,"HttpOnly","SameSite=Lax",
                "Path=/fgaisox/api/v1/infrastructure/agents/integrations/mcp","Max-Age=600").doesNotContain("Secure");
        assertThat(request.getSession(false)).isNull();
    }
    @Test void wrongOriginAndMissingCancelCookieMakeZeroAgentCalls() {
        var request=browser();request.removeHeader("Origin");
        assertThatThrownBy(()->controller.start(UUID.randomUUID(),Map.of(),request,new MockHttpServletResponse())).isInstanceOf(McpOAuthBrowserDeniedException.class);
        assertThatThrownBy(()->controller.cancel(UUID.randomUUID(),UUID.randomUUID(),Map.of(),browser(),new MockHttpServletResponse())).isInstanceOf(McpOAuthBrowserDeniedException.class);
        verifyNoInteractions(service);
    }
    @Test void callbackRequiresCookieAndRedirectsOnlyToFixedSafeResult() {
        UUID id=UUID.randomUUID(),tx=UUID.randomUUID();var request=new MockHttpServletRequest();request.setMethod("GET");
        request.setContextPath("/fgaisox");
        request.setRequestURI("/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback");
        request.addParameter("state",tx+".state-canary");request.addParameter("code","code-canary");
        assertThatThrownBy(()->controller.callback(request,new MockHttpServletResponse())).isInstanceOf(McpOAuthBrowserDeniedException.class);
        verifyNoInteractions(service);
        request.setCookies(new Cookie("ForgeMcpOAuth-"+tx,"binding-canary-32-characters-long"));
        when(service.completeOAuth(any())).thenReturn(new McpOAuthCompletion(id));
        var response=new MockHttpServletResponse();var result=controller.callback(request,response);
        assertThat(result.getStatusCode().value()).isEqualTo(303);
        assertThat(result.getHeaders().getLocation().toString()).isEqualTo("/fgaisox/operator/mcp-oauth-result.html?transactionId="+tx+"&connectionId="+id+"&result=connected");
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
        assertThat(result.toString()).doesNotContain("code-canary","state-canary","binding-canary");
    }
    private MockHttpServletRequest browser(){var r=new MockHttpServletRequest();r.addHeader("Origin","http://127.0.0.1:9099");r.setContentType("application/json");return r;}
}
