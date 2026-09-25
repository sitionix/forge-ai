package com.sitionix.forgeagent.mcp;

import com.sitionix.forgeagent.domain.port.McpGatewayRuntime;
import com.sitionix.forgeagent.application.mcp.McpGatewayAccessException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/mcp/connections/{connectionId}")
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
public final class McpGatewayController {
    private final McpGatewayRuntime runtime;
    private final McpGatewayProtocolAdapter protocol;
    private final int maxRequestBytes;

    public McpGatewayController(McpGatewayRuntime runtime, McpGatewayProtocolAdapter protocol,
            @Value("${forge.mcp.gateway.max-request-bytes:1048576}") int maxRequestBytes) {
        if (maxRequestBytes < 1024) throw new IllegalArgumentException("Invalid MCP gateway body limit");
        this.runtime = runtime;
        this.protocol = protocol;
        this.maxRequestBytes = maxRequestBytes;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> post(@PathVariable UUID connectionId, HttpServletRequest request) {
        Object value = request.getAttribute(McpGatewayRuntimeFilter.TOKEN_ATTRIBUTE);
        if (!(value instanceof String token)) return ResponseEntity.status(401).build();
        if (request.getContentLengthLong() > maxRequestBytes) return ResponseEntity.status(413).build();
        byte[] body;
        try { body = request.getInputStream().readNBytes(maxRequestBytes + 1); }
        catch (IOException unreadable) { return ResponseEntity.badRequest().build(); }
        if (body.length > maxRequestBytes) return ResponseEntity.status(413).build();
        com.sitionix.forgeagent.domain.model.McpRuntimeGrant grant;
        try { grant = runtime.authorize(token, connectionId); }
        catch (McpGatewayAccessException denied) { return ResponseEntity.status(401).build(); }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
        var result = protocol.process(grant, token, body);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").body(result.body());
    }
}
