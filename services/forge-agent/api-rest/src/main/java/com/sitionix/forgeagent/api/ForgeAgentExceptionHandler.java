package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ForgeAgentException;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ForgeAgentExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ForgeAgentErrorResponse> handleValidation(final ValidationException exception,
                                                             final HttpServletRequest request) {
        return this.response(HttpStatus.BAD_REQUEST, exception, request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ForgeAgentErrorResponse> handleNotFound(final NotFoundException exception,
                                                           final HttpServletRequest request) {
        return this.response(HttpStatus.NOT_FOUND, exception, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ForgeAgentErrorResponse> handleConflict(final ConflictException exception,
                                                           final HttpServletRequest request) {
        return this.response(HttpStatus.CONFLICT, exception, request);
    }

    @ExceptionHandler(InfrastructureExecutionException.class)
    ResponseEntity<ForgeAgentErrorResponse> handleInfrastructureExecution(final InfrastructureExecutionException exception,
                                                                          final HttpServletRequest request) {
        return this.response(HttpStatus.INTERNAL_SERVER_ERROR, exception, request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ForgeAgentErrorResponse> handleBadRequest(final Exception exception,
                                                             final HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(new ForgeAgentErrorResponse("INVALID_REQUEST", "Request body is invalid.", this.correlationId(request)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ForgeAgentErrorResponse> handleDataIntegrity(final DataIntegrityViolationException exception,
                                                                final HttpServletRequest request) {
        log.warn("Forge Agent persistence constraint rejected a request");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ForgeAgentErrorResponse("PERSISTENCE_CONFLICT", "Request conflicts with existing Forge Agent configuration data.", this.correlationId(request)));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ForgeAgentErrorResponse> handleRuntime(final RuntimeException exception,
                                                          final HttpServletRequest request) {
        log.error("Forge Agent request failed unexpectedly", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ForgeAgentErrorResponse("INTERNAL_ERROR", "Forge Agent request failed.", this.correlationId(request)));
    }

    private ResponseEntity<ForgeAgentErrorResponse> response(final HttpStatus status,
                                                            final ForgeAgentException exception,
                                                            final HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ForgeAgentErrorResponse(exception.code(), exception.getMessage(), this.correlationId(request)));
    }

    private String correlationId(final HttpServletRequest request) {
        return request.getHeader("X-Correlation-ID");
    }
}
