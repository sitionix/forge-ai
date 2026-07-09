package com.sitionix.forgeai.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiInfrastructureJarvisController {

    private final InfrastructureProxyTransport proxyTransport;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/v1/infrastructure/jarvis/status")
    public CompletableFuture<ResponseEntity<byte[]>> status(@RequestHeader final HttpHeaders headers,
                                                            final HttpServletRequest request) {
        return this.proxy("jarvis.status", null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/jarvis/actions")
    public CompletableFuture<ResponseEntity<byte[]>> actions(@RequestHeader final HttpHeaders headers,
                                                             final HttpServletRequest request) {
        return this.proxy("jarvis.actions", null, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/jarvis/command")
    public CompletableFuture<ResponseEntity<byte[]>> command(@RequestBody(required = false) final byte[] body,
                                                             @RequestHeader final HttpHeaders headers,
                                                             final HttpServletRequest request) {
        return this.proxy("jarvis.command", body, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/jarvis/query")
    public CompletableFuture<ResponseEntity<byte[]>> query(@RequestBody(required = false) final JarvisKnowledgeQueryRequest body,
                                                           @RequestHeader final HttpHeaders headers,
                                                           final HttpServletRequest request) {
        final ResponseEntity<byte[]> validationError = this.validate(body);
        if (validationError != null) {
            return CompletableFuture.completedFuture(validationError);
        }
        return this.proxy("jarvis.query", this.write(body), headers, request);
    }

    private CompletableFuture<ResponseEntity<byte[]>> proxy(final String route,
                                                            final byte[] body,
                                                            final HttpHeaders headers,
                                                            final HttpServletRequest request) {
        return this.proxyTransport.forward(route, Map.of(), body, headers, request);
    }

    private byte[] write(final JarvisKnowledgeQueryRequest body) {
        try {
            return this.objectMapper.writeValueAsBytes(body);
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Jarvis query request could not be serialized.", exception);
        }
    }

    private ResponseEntity<byte[]> validate(final JarvisKnowledgeQueryRequest body) {
        if (body == null) {
            return this.validationError("queryText must not be blank");
        }
        if (body.queryText() == null || body.queryText().isBlank()) {
            return this.validationError("queryText must not be blank");
        }
        if (body.intent() == null) {
            return this.validationError("intent is required");
        }
        if (body.answerLanguage() == null || body.answerLanguage().isBlank()) {
            return this.validationError("answerLanguage must not be blank");
        }
        if (body.includeTests() == null) {
            return this.validationError("includeTests is required");
        }
        if (body.maxFlows() == null || body.maxFlows() < 1 || body.maxFlows() > 10) {
            return this.validationError("maxFlows must be between 1 and 10");
        }
        return null;
    }

    private ResponseEntity<byte[]> validationError(final String details) {
        final byte[] body;
        try {
            body = this.objectMapper.writeValueAsBytes(Map.of(
                    "code", HttpStatus.BAD_REQUEST.value(),
                    "title", "VALIDATION_FAILED",
                    "details", details
            ));
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Jarvis query validation response could not be serialized.", exception);
        }
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, headers, HttpStatus.BAD_REQUEST);
    }
}
