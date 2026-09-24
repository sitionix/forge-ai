package com.sitionix.forgeagent.api.mcp;

import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import java.util.NoSuchElementException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={McpConnectionsController.class,McpAvailableController.class})
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
    private static ResponseEntity<ForgeAgentErrorResponse> response(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).body(new ForgeAgentErrorResponse(code,message,null));
    }
}
