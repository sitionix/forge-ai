package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.exception.ServicePropertyMissingException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(LaneConflictException.class)
    public ResponseEntity<Map<String, String>> handleLaneConflictException(final LaneConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "lane_conflict",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTicketNotFoundException(final TicketNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "ticket_not_found",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(LaneNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleLaneNotFoundException(final LaneNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "lane_not_found",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<Map<String, String>> handleRequestValidationException(final RequestValidationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "request_validation_failed",
                        "message", exception.getMessage()
                ));
    }
}
