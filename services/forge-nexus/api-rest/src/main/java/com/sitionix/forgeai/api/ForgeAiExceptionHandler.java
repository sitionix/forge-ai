package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.exception.TicketNotFoundException;
import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.sitionix.forgeai.domain.exception.ApiLaneEvidenceValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.sitionix.forgeai.domain.exception.ServicePropertyMissingException;

@RestControllerAdvice
public class ForgeAiExceptionHandler {

    @ExceptionHandler(ServicePropertyMissingException.class)
    public ResponseEntity<Map<String, String>> handleServicePropertyMissing(final ServicePropertyMissingException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "service_config_property_missing",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, String>> handleNullPointerException(final NullPointerException exception) {
        final StackTraceElement[] stackTrace = exception.getStackTrace();
        final boolean servicePropsRelated = stackTrace != null
                && stackTrace.length > 0
                && stackTrace[0].getClassName().contains("ServiceProps");
        if (!servicePropsRelated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "internal_error",
                            "message", "Unexpected internal error"
                    ));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "service_config_property_missing",
                        "message", "Service configuration is incomplete"
                ));
    }

    @ExceptionHandler(ScopeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleScopeMismatchException(final ScopeMismatchException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "scope_mismatch",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(ApiLaneEvidenceValidationException.class)
    public ResponseEntity<Map<String, String>> handleApiLaneEvidenceValidationException(final ApiLaneEvidenceValidationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", exception.getCode(),
                        "message", exception.getMessage(),
                        "hint", exception.getHint()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValidException(final MethodArgumentNotValidException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "code", HttpStatus.BAD_REQUEST.value(),
                        "title", "VALIDATION_FAILED",
                        "details", this.buildValidationDetails(exception.getBindingResult().getFieldErrors())
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadableException(final HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "code", HttpStatus.BAD_REQUEST.value(),
                        "title", "VALIDATION_FAILED",
                        "details", "Request body is invalid or does not match the expected contract"
                ));
    }

    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTicketNotFound(final TicketNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "title", "NOT_FOUND",
                        "details", exception.getMessage()
                ));
    }

    private String buildValidationDetails(final List<FieldError> fieldErrors) {
        if (fieldErrors.isEmpty()) {
            return "Validation failed";
        }
        return fieldErrors.stream()
                .map(fieldError -> String.format(
                        "%s %s (rejected: %s)",
                        fieldError.getField(),
                        fieldError.getDefaultMessage(),
                        fieldError.getRejectedValue()))
                .collect(Collectors.joining("; "));
    }
}
