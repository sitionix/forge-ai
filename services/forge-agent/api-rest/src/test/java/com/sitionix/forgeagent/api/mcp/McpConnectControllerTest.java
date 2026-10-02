package com.sitionix.forgeagent.api.mcp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.mcp.McpConnectService;
import java.net.URI;
import org.junit.jupiter.api.Test;
class McpConnectControllerTest {
    @Test void typedRequestDelegatesAndBrowserBindingCannotBeSerialized() throws Exception {
        var service=mock(McpConnectService.class);var controller=new McpConnectController(service);
        var mapper=new ObjectMapper();var request=mapper.readValue("{\"displayName\":\"Name\",\"endpoint\":\"https://mcp.example/mcp\",\"browserBinding\":\"binding-canary\"}",McpConnectController.Request.class);
        var connection=new com.sitionix.forgeagent.domain.model.McpConnection(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"Name",request.endpoint(),com.sitionix.forgeagent.domain.model.McpAuthType.NONE,false,com.sitionix.forgeagent.domain.model.McpProjectAccess.selected(java.util.Set.of()),java.util.Set.of(),false,java.time.Instant.now(),java.time.Instant.now(),null,null,null,null);
        when(service.connect("Name",request.endpoint(),"binding-canary")).thenReturn(new com.sitionix.forgeagent.domain.model.McpConnectResult(connection,null));
        controller.connect(request);verify(service).connect("Name",URI.create("https://mcp.example/mcp"),"binding-canary");
        assertThat(request.toString()+mapper.writeValueAsString(request)).doesNotContain("binding-canary");
    }
}
