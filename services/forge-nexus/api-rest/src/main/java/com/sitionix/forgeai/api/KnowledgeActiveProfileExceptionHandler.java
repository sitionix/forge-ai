package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = KnowledgeActiveProfileController.class)
@RequiredArgsConstructor
public final class KnowledgeActiveProfileExceptionHandler {

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String VALIDATION_MESSAGE = "Active LLM profile request is invalid.";
    private static final String UNREADABLE_MESSAGE = "Request body is invalid or does not match the expected contract.";

    private final CorrelationIdProvider correlationIdProvider;

    @ExceptionHandler(KnowledgeActiveProfileClientException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleKnowledgeActiveProfileClientException(
            final KnowledgeActiveProfileClientException exception
    ) {
        final String correlationId = this.correlationId(exception.correlationId());
        return ResponseEntity.status(this.httpStatus(exception.reason()))
                .header(CorrelationIdProvider.HEADER_NAME, correlationId)
                .body(new InfrastructureErrorResponse(
                        exception.code(),
                        exception.getMessage(),
                        correlationId
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleMethodArgumentNotValidException(
            final MethodArgumentNotValidException exception
    ) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(CorrelationIdProvider.HEADER_NAME, correlationId)
                .body(new InfrastructureErrorResponse(
                        VALIDATION_FAILED,
                        VALIDATION_MESSAGE,
                        correlationId
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleHttpMessageNotReadableException(
            final HttpMessageNotReadableException exception
    ) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(CorrelationIdProvider.HEADER_NAME, correlationId)
                .body(new InfrastructureErrorResponse(
                        VALIDATION_FAILED,
                        UNREADABLE_MESSAGE,
                        correlationId
                ));
    }

    private String correlationId(final String supplied) {
        if (CorrelationIdProvider.isValid(supplied)) {
            return supplied;
        }
        return this.correlationIdProvider.currentOrCreate();
    }

    private HttpStatus httpStatus(final KnowledgeActiveProfileFailureReason reason) {
        return switch (reason) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE_ENTITY -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INVALID_RESPONSE, UPSTREAM_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
