package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
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
    public CompletableFuture<ResponseEntity<byte[]>> query(@RequestBody(required = false) final byte[] body,
                                                           @RequestHeader final HttpHeaders headers,
                                                           final HttpServletRequest request) {
        return this.proxy("jarvis.query", body, headers, request);
    }

    private CompletableFuture<ResponseEntity<byte[]>> proxy(final String route,
                                                            final byte[] body,
                                                            final HttpHeaders headers,
                                                            final HttpServletRequest request) {
        return this.proxyTransport.forward(route, Map.of(), body, headers, request);
    }

}
