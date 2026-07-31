package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = KnowledgeActiveProfileController.class)
public class KnowledgeActiveProfileExceptionHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @ExceptionHandler(KnowledgeActiveProfileClientException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleKnowledgeActiveProfileClientException(
            final KnowledgeActiveProfileClientException exception,
            final HttpServletRequest request
    ) {
        return ResponseEntity.status(this.httpStatus(exception.status()))
                .body(new InfrastructureErrorResponse(
                        exception.code(),
                        exception.getMessage(),
                        this.correlationId(exception.correlationId(), request)
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleMethodArgumentNotValidException(
            final MethodArgumentNotValidException exception,
            final HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new InfrastructureErrorResponse(
                        "VALIDATION_FAILED",
                        this.validationMessage(exception),
                        this.correlationId(null, request)
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InfrastructureErrorResponse> handleHttpMessageNotReadableException(
            final HttpMessageNotReadableException exception,
            final HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new InfrastructureErrorResponse(
                        "VALIDATION_FAILED",
                        "Request body is invalid or does not match the expected contract",
                        this.correlationId(null, request)
                ));
    }

    private HttpStatus httpStatus(final int status) {
        try {
            return HttpStatus.valueOf(status);
        } catch (final IllegalArgumentException exception) {
            return HttpStatus.BAD_GATEWAY;
        }
    }

    private String validationMessage(final MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::validationMessage)
                .orElse("Request body is invalid or does not match the expected contract");
    }

    private String validationMessage(final FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }

    private String correlationId(final String supplied, final HttpServletRequest request) {
        if (this.validCorrelationId(supplied)) {
            return supplied;
        }
        final String incoming = request == null ? null : request.getHeader(CORRELATION_HEADER);
        if (this.validCorrelationId(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    private boolean validCorrelationId(final String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
    }
}
