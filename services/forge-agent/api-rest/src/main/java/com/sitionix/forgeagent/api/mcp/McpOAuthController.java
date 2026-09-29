package com.sitionix.forgeagent.api.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sitionix.forgeagent.application.mcp.McpOAuthService;
import com.sitionix.forgeagent.domain.model.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/mcp")
public class McpOAuthController {
    private final McpOAuthService service;
    public McpOAuthController(McpOAuthService service) { this.service = service; }
    @PostMapping("/connections/{id}/oauth/start")
    public McpOAuthStart start(@PathVariable UUID id, @RequestBody Start request) {
        if (request == null) throw new IllegalArgumentException("Invalid OAuth request");
        return service.start(id,request.browserBinding());
    }
    @DeleteMapping("/connections/{id}/oauth/transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id,@PathVariable UUID transactionId,@RequestBody Start request) {
        if (request == null) throw new IllegalArgumentException("Invalid OAuth request");
        service.cancel(id,transactionId,request.browserBinding());
    }
    @PostMapping("/oauth/callback")
    public McpOAuthCompletion complete(@RequestBody Callback request) {
        if (request == null) throw new IllegalArgumentException("Invalid OAuth request");
        return service.complete(new McpOAuthCallback(request.state(),request.browserBinding(),request.code(),request.error(),request.issuer()));
    }
    public record Start(@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String browserBinding) {
        @Override public String toString() { return "OAuthStart[redacted]"; }
    }
    public record Callback(@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String state,
                           @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String browserBinding,
                           @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String code,
                           @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String error,
                           @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String issuer) {
        @Override public String toString() { return "OAuthCallback[redacted]"; }
    }
}
