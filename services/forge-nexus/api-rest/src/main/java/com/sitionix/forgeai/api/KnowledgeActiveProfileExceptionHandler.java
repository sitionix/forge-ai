package com.sitionix.forgeai.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = KnowledgeActiveProfileController.class)
@RequiredArgsConstructor
public class KnowledgeActiveProfileExceptionHandler {

    private static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";
    private static final String UPSTREAM_INVALID_RESPONSE = "UPSTREAM_INVALID_RESPONSE";
    private static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private final ObjectMapper objectMapper;

    @ExceptionHandler(KnowledgeClientException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleKnowledgeClientException(
            final KnowledgeClientException exception
    ) {
        final int statusCode = exception.statusCode();
        if (!this.validHttpStatus(statusCode)) {
            log.error("Knowledge active-profile upstream returned invalid HTTP status: {}", statusCode, exception);
            return this.response(HttpStatus.BAD_GATEWAY.value(), UPSTREAM_ERROR, "Knowledge request failed.", null);
        }
        final InfrastructureErrorResponse error = this.parseError(exception.responseBody());
        if (error == null || !this.hasText(error.code()) || !this.hasText(error.message())) {
            log.warn("Knowledge active-profile upstream returned malformed error body. upstreamStatus={}", statusCode, exception);
            return this.response(
                    HttpStatus.BAD_GATEWAY.value(),
                    UPSTREAM_INVALID_RESPONSE,
                    "Knowledge service returned an invalid response.",
                    null
            );
        }
        return this.response(statusCode, error.code(), error.message(), error.correlationId());
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleResourceAccessException(
            final ResourceAccessException exception
    ) {
        log.warn("Knowledge active-profile service is unavailable", exception);
        return this.response(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                UPSTREAM_UNAVAILABLE,
                "Knowledge service is unavailable.",
                null
        );
    }

    @ExceptionHandler({RestClientException.class, HttpMessageConversionException.class})
    public ResponseEntity<InfrastructureErrorResponse> handleInvalidUpstreamResponse(final RuntimeException exception) {
        log.warn("Knowledge active-profile service returned an invalid response", exception);
        return this.response(
                HttpStatus.BAD_GATEWAY.value(),
                UPSTREAM_INVALID_RESPONSE,
                "Knowledge service returned an invalid response.",
                null
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleHttpMessageNotReadableException(
            final HttpMessageNotReadableException exception
    ) {
        return this.response(
                HttpStatus.BAD_REQUEST.value(),
                VALIDATION_FAILED,
                "Request body is invalid or does not match the expected contract.",
                null
        );
    }

    private InfrastructureErrorResponse parseError(final String responseBody) {
        try {
            return this.objectMapper.readValue(responseBody, InfrastructureErrorResponse.class);
        } catch (final JsonProcessingException | RuntimeException parsingException) {
            return null;
        }
    }

    private ResponseEntity<InfrastructureErrorResponse> response(final int status,
                                                                 final String code,
                                                                 final String message,
                                                                 final String correlationId) {
        return ResponseEntity.status(status)
                .body(new InfrastructureErrorResponse(code, message, correlationId));
    }

    private boolean validHttpStatus(final int statusCode) {
        return statusCode >= 100 && statusCode <= 599;
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
