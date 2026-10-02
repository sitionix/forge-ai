package com.sitionix.forgeagent.api.mcp;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sitionix.forgeagent.application.mcp.McpConnectService;
import com.sitionix.forgeagent.domain.model.McpOAuthStart;
import java.net.URI;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/integrations/mcp")
public class McpConnectController {
    private final McpConnectService service;
    public McpConnectController(McpConnectService service){this.service=service;}
    @PostMapping("/connect") public Response connect(@RequestBody Request request) {
        if(request==null)throw new IllegalArgumentException("Invalid MCP Connect request");
        var result=service.connect(request.displayName(),request.endpoint(),request.browserBinding());
        return new Response(McpConnectionResponse.from(result.connection()),result.authorization());
    }
    public record Response(McpConnectionResponse connection,McpOAuthStart authorization){}
    public record Request(String displayName,URI endpoint,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String browserBinding){
        @Override public String toString(){return "McpConnectRequest[redacted]";}
    }
}
