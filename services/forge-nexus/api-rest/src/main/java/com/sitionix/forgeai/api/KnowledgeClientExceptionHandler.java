package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public final class KnowledgeClientExceptionHandler {

    private static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";
    private static final String UPSTREAM_FAILURE_MESSAGE = "Knowledge request failed.";

    private final HttpServletRequest request;

    @ExceptionHandler(KnowledgeClientException.class)
    public ResponseEntity<InfrastructureErrorResponse> handle(final KnowledgeClientException exception) {
        final HttpStatus status = HttpStatus.resolve(exception.statusCode());
        if (status == null) {
            log.error("Knowledge client produced invalid HTTP status: {}", exception.statusCode(), exception);
            return this.response(
                    HttpStatus.BAD_GATEWAY,
                    UPSTREAM_ERROR,
                    UPSTREAM_FAILURE_MESSAGE,
                    this.currentCorrelationId()
            );
        }
        final String correlationId = this.preserveOrCurrent(exception.correlationId());
        return this.response(status, exception.code(), exception.getMessage(), correlationId);
    }

    private ResponseEntity<InfrastructureErrorResponse> response(final HttpStatus status,
                                                                 final String code,
                                                                 final String message,
                                                                 final String correlationId) {
        return ResponseEntity.status(status)
                .header(NexusCorrelationFilter.CORRELATION_HEADER, correlationId)
                .body(new InfrastructureErrorResponse(code, message, correlationId));
    }

    private String preserveOrCurrent(final String supplied) {
        if (NexusCorrelationFilter.valid(supplied)) {
            return supplied;
        }
        return this.currentCorrelationId();
    }

    private String currentCorrelationId() {
        if (this.request.getAttribute(NexusCorrelationFilter.CORRELATION_ATTRIBUTE) instanceof String correlationId
                && NexusCorrelationFilter.valid(correlationId)) {
            return correlationId;
        }
        final String header = this.request.getHeader(NexusCorrelationFilter.CORRELATION_HEADER);
        if (NexusCorrelationFilter.valid(header)) {
            return header;
        }
        return "unavailable";
    }
}
