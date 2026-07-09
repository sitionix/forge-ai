package com.sitionix.forgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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

    @GetMapping("/api/v1/infrastructure/jarvis/status")
    public CompletableFuture<ResponseEntity<?>> status(@RequestHeader final HttpHeaders headers,
                                                       final HttpServletRequest request) {
        return this.proxy("jarvis.status", null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/jarvis/actions")
    public CompletableFuture<ResponseEntity<?>> actions(@RequestHeader final HttpHeaders headers,
                                                        final HttpServletRequest request) {
        return this.proxy("jarvis.actions", null, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/jarvis/command")
    public CompletableFuture<ResponseEntity<?>> command(@RequestBody(required = false) final JsonNode body,
                                                        @RequestHeader final HttpHeaders headers,
                                                        final HttpServletRequest request) {
        return this.proxy("jarvis.command", body, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/jarvis/query")
    public CompletableFuture<ResponseEntity<?>> query(@Valid @RequestBody final JarvisKnowledgeQueryRequest body,
                                                      @RequestHeader final HttpHeaders headers,
                                                      final HttpServletRequest request) {
        return this.proxyTransport.forwardJson("jarvis.query", Map.of(), body.normalized(), JarvisKnowledgeQueryResponse.class, headers, request);
    }

    private CompletableFuture<ResponseEntity<?>> proxy(final String route,
                                                       final JsonNode body,
                                                       final HttpHeaders headers,
                                                       final HttpServletRequest request) {
        return this.proxyTransport.forwardJson(route, Map.of(), body, JsonNode.class, headers, request);
    }
}
