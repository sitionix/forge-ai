package com.sitionix.forgeai.api.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import java.io.IOException;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ForgeAiMcpConnectionsController.class)
public class McpConnectionsExceptionHandler {
    private final ObjectMapper objectMapper;

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<InfrastructureErrorResponse> invalid() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "MCP request is invalid.");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<InfrastructureErrorResponse> missing() {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "MCP connection not found.");
    }

    @ExceptionHandler(AgentClientException.class)
    public ResponseEntity<InfrastructureErrorResponse> upstream(AgentClientException exception) {
        int status = exception.statusCode();
        InfrastructureErrorResponse error = parseError(exception.responseBody());
        if (status < 400 || status > 599 || error == null || !hasText(error.code()) || !hasText(error.message())) {
            return invalidUpstream();
        }
        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<InfrastructureErrorResponse> unavailable() {
        log.warn("Forge Agent service is unavailable");
        return response(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "Forge Agent service is unavailable.");
    }

    @ExceptionHandler({RestClientException.class, HttpMessageConversionException.class})
    public ResponseEntity<InfrastructureErrorResponse> invalidTransportResponse() {
        return invalidUpstream();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<InfrastructureErrorResponse> failed() {
        return invalidUpstream();
    }

    private InfrastructureErrorResponse parseError(String body) {
        if (body == null) return null;
        try (JsonParser parser = objectMapper.getFactory().createParser(body)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(parser);
            if (node == null || !node.isObject() || node.size() < 2 || node.size() > 3
                    || !node.has("code") || !node.get("code").isTextual()
                    || !node.has("message") || !node.get("message").isTextual()) return null;
            var names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!name.equals("code") && !name.equals("message") && !name.equals("correlationId")) return null;
            }
            JsonNode correlation = node.get("correlationId");
            if (correlation != null && !correlation.isNull() && !correlation.isTextual()) return null;
            return objectMapper.treeToValue(node, InfrastructureErrorResponse.class);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private ResponseEntity<InfrastructureErrorResponse> invalidUpstream() {
        log.warn("Forge Agent service returned an invalid response");
        return response(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID_RESPONSE",
                "Forge Agent service returned an invalid response.");
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private static ResponseEntity<InfrastructureErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new InfrastructureErrorResponse(code, message, null));
    }
}
