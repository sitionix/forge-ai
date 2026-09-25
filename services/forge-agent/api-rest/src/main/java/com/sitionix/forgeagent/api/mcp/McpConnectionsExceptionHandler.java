package com.sitionix.forgeagent.api.mcp;

import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import java.util.NoSuchElementException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={McpConnectionsController.class,McpAvailableController.class,McpProbeController.class})
public class McpConnectionsExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<ForgeAgentErrorResponse> missing() {
        return response(HttpStatus.NOT_FOUND,"NOT_FOUND","MCP connection not found.");
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ForgeAgentErrorResponse> invalid() { return response(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","MCP request is invalid."); }
    @ExceptionHandler(Exception.class) public ResponseEntity<ForgeAgentErrorResponse> failed() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,"MCP_OPERATION_FAILED","MCP management operation failed.");
    }
    @ExceptionHandler(com.sitionix.forgeagent.domain.exception.McpRegistryUnavailableException.class)
    public ResponseEntity<ForgeAgentErrorResponse> registryUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE,"MCP_REGISTRY_UNAVAILABLE","MCP Registry is unavailable.");
    }
    @ExceptionHandler(com.sitionix.forgeagent.domain.exception.McpProbeException.class)
    public ResponseEntity<ForgeAgentErrorResponse> probeFailure(com.sitionix.forgeagent.domain.exception.McpProbeException failure) {
        return switch (failure.reason()) {
            case AUTH_REQUIRED -> response(HttpStatus.UNAUTHORIZED, "MCP_AUTH_REQUIRED", "MCP authorization is required.");
            case FORBIDDEN -> response(HttpStatus.FORBIDDEN, "MCP_FORBIDDEN", "MCP access is forbidden.");
            case UNSUPPORTED_PROTOCOL -> response(HttpStatus.BAD_GATEWAY, "MCP_UNSUPPORTED_PROTOCOL", "MCP protocol is unsupported.");
            case INVALID_RESPONSE -> response(HttpStatus.BAD_GATEWAY, "MCP_INVALID_RESPONSE", "MCP server response is invalid.");
            case UNAVAILABLE -> response(HttpStatus.SERVICE_UNAVAILABLE, "MCP_UNAVAILABLE", "MCP server is unavailable.");
            case ENDPOINT_DENIED -> response(HttpStatus.BAD_REQUEST, "MCP_ENDPOINT_DENIED", "MCP endpoint is not allowed.");
        };
    }
    private static ResponseEntity<ForgeAgentErrorResponse> response(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).body(new ForgeAgentErrorResponse(code,message,null));
    }
}
