package com.sitionix.forgeai.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
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

    private static final Pattern LANGUAGE_CODE_PATTERN = Pattern.compile("^[a-z]{2,3}$");

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
    public CompletableFuture<ResponseEntity<byte[]>> query(@Valid @RequestBody(required = false) final JarvisKnowledgeQueryRequest body,
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
            final Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("queryText", body.queryText());
            if (body.intent() != null) {
                payload.put("intent", body.intent());
            }
            if (body.answerLanguage() != null) {
                payload.put("answerLanguage", body.answerLanguage());
            }
            if (body.includeTests() != null) {
                payload.put("includeTests", body.includeTests());
            }
            if (body.maxFlows() != null) {
                payload.put("maxFlows", body.maxFlows());
            }
            return this.objectMapper.writeValueAsBytes(payload);
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
        if (body.maxFlows() != null && (body.maxFlows() < 1 || body.maxFlows() > 10)) {
            return this.validationError("maxFlows must be between 1 and 10");
        }
        if (!this.validLanguageCode(body.answerLanguage())) {
            return this.validationError("answerLanguage must be omitted, null, auto, or a valid language code");
        }
        return null;
    }

    private boolean validLanguageCode(final String answerLanguage) {
        if (answerLanguage == null || answerLanguage.isBlank()) {
            return true;
        }
        final String normalized = answerLanguage.trim().toLowerCase();
        if ("auto".equals(normalized)) {
            return true;
        }
        final String primarySubtag = normalized.split("-", 2)[0];
        return LANGUAGE_CODE_PATTERN.matcher(primarySubtag).matches();
    }

    private ResponseEntity<byte[]> validationError(final String details) {
        final byte[] body;
        try {
            body = this.objectMapper.writeValueAsBytes(Map.of(
                    "code", HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "title", "VALIDATION_FAILED",
                    "details", details
            ));
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Jarvis query validation response could not be serialized.", exception);
        }
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, headers, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
