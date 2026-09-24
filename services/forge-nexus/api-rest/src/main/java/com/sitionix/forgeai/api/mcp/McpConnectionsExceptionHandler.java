package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.McpAgentClientException;
import java.util.NoSuchElementException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ForgeAiMcpConnectionsController.class)
public class McpConnectionsExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<InfrastructureErrorResponse> invalid(){return response(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","MCP request is invalid.");}
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<InfrastructureErrorResponse> missing(){return response(HttpStatus.NOT_FOUND,"NOT_FOUND","MCP connection not found.");}
    @ExceptionHandler(McpAgentClientException.class) public ResponseEntity<InfrastructureErrorResponse> upstream(McpAgentClientException error){
        return switch(error.category()){
            case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","MCP request is invalid.");
            case NOT_FOUND -> response(HttpStatus.NOT_FOUND,"NOT_FOUND","MCP connection not found.");
            case UPSTREAM_UNAVAILABLE -> response(HttpStatus.SERVICE_UNAVAILABLE,"UPSTREAM_UNAVAILABLE","Forge Agent service is unavailable.");
            case UPSTREAM_ERROR -> response(HttpStatus.BAD_GATEWAY,"UPSTREAM_ERROR","MCP management operation failed.");
        };
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<InfrastructureErrorResponse> failed(){return response(HttpStatus.BAD_GATEWAY,"UPSTREAM_ERROR","MCP management operation failed.");}
    private static ResponseEntity<InfrastructureErrorResponse> response(HttpStatus status,String code,String message){return ResponseEntity.status(status).body(new InfrastructureErrorResponse(code,message,null));}
}
