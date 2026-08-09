package com.sitionix.forgeai.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ForgeAiInfrastructureAgentsController.class)
@RequiredArgsConstructor
public class ForgeAiInfrastructureAgentsExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(AgentClientException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleAgentClientException(final AgentClientException exception) {
        final int statusCode = exception.statusCode();
        if (statusCode < 100 || statusCode > 599) {
            return this.response(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Forge Agent request failed.", null);
        }
        final InfrastructureErrorResponse error = this.parseError(exception.responseBody());
        if (error == null || !this.hasText(error.code()) || !this.hasText(error.message())) {
            return this.response(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID_RESPONSE", "Forge Agent service returned an invalid response.", null);
        }
        return ResponseEntity.status(statusCode).body(error);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleResourceAccessException(final ResourceAccessException exception) {
        log.warn("Forge Agent service is unavailable", exception);
        return this.response(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "Forge Agent service is unavailable.", null);
    }

    @ExceptionHandler({RestClientException.class, HttpMessageConversionException.class})
    public ResponseEntity<InfrastructureErrorResponse> handleInvalidUpstreamResponse(final RuntimeException exception) {
        log.warn("Forge Agent service returned an invalid response", exception);
        return this.response(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID_RESPONSE", "Forge Agent service returned an invalid response.", null);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<InfrastructureErrorResponse> handleBadRequest(final Exception exception) {
        return this.response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Agent request is invalid.", null);
    }

    private InfrastructureErrorResponse parseError(final String responseBody) {
        try {
            return this.objectMapper.readValue(responseBody, InfrastructureErrorResponse.class);
        } catch (final JsonProcessingException | RuntimeException exception) {
            return null;
        }
    }

    private ResponseEntity<InfrastructureErrorResponse> response(final HttpStatus status,
                                                                 final String code,
                                                                 final String message,
                                                                 final String correlationId) {
        return ResponseEntity.status(status).body(new InfrastructureErrorResponse(code, message, correlationId));
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
