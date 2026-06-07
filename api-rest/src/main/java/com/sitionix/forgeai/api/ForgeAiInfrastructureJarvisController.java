package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.domain.exception.JarvisGatewayException;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
import com.sitionix.forgeai.domain.usecase.ManageJarvisInfrastructure;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiInfrastructureJarvisController {

    private final ManageJarvisInfrastructure manageJarvisInfrastructure;

    @GetMapping("/api/v1/infrastructure/jarvis/status")
    public ResponseEntity<JarvisStatusView> status() {
        return ResponseEntity.ok(this.manageJarvisInfrastructure.status());
    }

    @GetMapping("/api/v1/infrastructure/jarvis/actions")
    public ResponseEntity<JarvisActionsView> actions() {
        return ResponseEntity.ok(this.manageJarvisInfrastructure.actions());
    }

    @PostMapping("/api/v1/infrastructure/jarvis/command")
    public ResponseEntity<JarvisCommandResultView> command(@RequestBody final JarvisCommandRequest request) {
        return ResponseEntity.ok(this.manageJarvisInfrastructure.command(request));
    }

    @ExceptionHandler(JarvisGatewayException.class)
    public ResponseEntity<JarvisErrorResponse> handleJarvisGatewayException(final JarvisGatewayException exception) {
        return ResponseEntity
                .status(this.httpStatus(exception.getCode()))
                .body(new JarvisErrorResponse(exception.getCode().name(), exception.getMessage()));
    }

    private HttpStatus httpStatus(final JarvisGatewayErrorCode code) {
        return switch (code) {
            case INVALID_COMMAND -> HttpStatus.BAD_REQUEST;
            case JARVIS_UNAVAILABLE, OLLAMA_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case JARVIS_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case JARVIS_BAD_RESPONSE, ACTION_EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
            case UNSUPPORTED_ACTION -> HttpStatus.FORBIDDEN;
        };
    }

    public record JarvisErrorResponse(String code, String message) {
    }
}
