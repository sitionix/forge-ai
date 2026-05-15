package com.sitionix.forgeai.api;

import com.sitionix.forgeai.config.ServicePropertyMissingException;
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
}
