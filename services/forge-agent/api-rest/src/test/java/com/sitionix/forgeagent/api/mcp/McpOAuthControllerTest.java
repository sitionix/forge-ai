package com.sitionix.forgeagent.api.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.mcp.McpOAuthService;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpOAuthControllerTest {
    @Test void delegatesOnlyTypedBrowserLifecycleAndReturnsSafeMetadata() {
        var service = mock(McpOAuthService.class); var controller = new McpOAuthController(service);
        var id = UUID.randomUUID(); var tx = UUID.randomUUID();
        var start = new McpOAuthStart(tx,id,URI.create("https://provider.example/authorize"));
        when(service.start(id,"browser-canary")).thenReturn(start);
        assertThat(controller.start(id,new McpOAuthController.Start("browser-canary"))).isEqualTo(start);
        controller.cancel(id,tx,new McpOAuthController.Start("browser-canary"));
        verify(service).cancel(id,tx,"browser-canary");
    }
    @Test void callbackAndSetupCredentialsAreWriteOnlyAndRedacted() throws Exception {
        var mapper = new ObjectMapper();
        var callback = mapper.readValue("{\"state\":\"state-canary\",\"browserBinding\":\"browser-canary\",\"code\":\"code-canary\"}",McpOAuthController.Callback.class);
        assertThat(callback.toString()+mapper.writeValueAsString(callback)).doesNotContain("canary");
        var setup = mapper.readValue("{\"credential\":{\"clientSecret\":\"client-canary\"}}",McpConnectionRequest.class);
        assertThat(setup.credential().clientSecret()).isEqualTo("client-canary");
        assertThat(setup.toString()+mapper.writeValueAsString(setup)).doesNotContain("canary");
    }
    @Test void oauthErrorsHaveSafeScopedContract() {
        var response = new McpConnectionsExceptionHandler().oauthFailure(McpOAuthException.denied());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().code()).isEqualTo("MCP_OAUTH_DENIED");
        assertThat(response.getBody().message()).isEqualTo("OAuth authorization was declined.");
    }
}
