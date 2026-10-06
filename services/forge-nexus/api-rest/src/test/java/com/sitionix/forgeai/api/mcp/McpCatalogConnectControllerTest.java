package com.sitionix.forgeai.api.mcp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.domain.usecase.ManageAgentMcpConnections;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
class McpCatalogConnectControllerTest {
    final ManageAgentMcpConnections service=mock(ManageAgentMcpConnections.class);
    final ForgeAiMcpOAuthController controller=new ForgeAiMcpOAuthController(service,new McpOAuthBrowserProperties(URI.create("http://127.0.0.1:9099"),Duration.ofMinutes(10)),new McpApiMapper());
    @Test void noAuthResultNeedsNoBrowserCookie(){
        var c=new McpConnection(UUID.randomUUID(),"Name",URI.create("https://mcp.example/mcp"),McpConnection.Transport.STREAMABLE_HTTP,McpConnection.AuthType.NONE,false,new McpConnection.ProjectAccess(McpConnection.Scope.SELECTED,Set.of()),Set.of(),false,Instant.now(),Instant.now(),null,null,null);
        when(service.connect(any(),anyString())).thenReturn(new McpConnectResult(c,null));var response=new MockHttpServletResponse();
        var result=controller.connect(new ForgeAiMcpOAuthController.ConnectRequest("Name",c.endpoint()),browser(),response);
        assertThat(result.connection().id()).isEqualTo(c.id());assertThat(response.getHeader("Set-Cookie")).isNull();assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void wrongOriginAndUnsupportedBrowserFieldsMakeZeroCalls() throws Exception {
        var request=browser();request.removeHeader("Origin");
        assertThatThrownBy(()->controller.connect(new ForgeAiMcpOAuthController.ConnectRequest("Name",URI.create("https://mcp.example/mcp")),request,new MockHttpServletResponse())).isInstanceOf(McpOAuthBrowserDeniedException.class);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for(String property:List.of("browserBinding","oauthConfiguration","clientSecret"))assertThatThrownBy(()->mapper.readValue("{\"displayName\":\"Name\",\"endpoint\":\"https://mcp.example/mcp\",\""+property+"\":\"synthetic-canary\"}",ForgeAiMcpOAuthController.ConnectRequest.class)).isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        verifyNoInteractions(service);
    }
    MockHttpServletRequest browser(){var r=new MockHttpServletRequest();r.addHeader("Origin","http://127.0.0.1:9099");r.setContentType("application/json");return r;}
}
