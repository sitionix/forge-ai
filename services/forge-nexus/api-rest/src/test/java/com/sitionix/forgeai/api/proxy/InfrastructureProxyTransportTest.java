package com.sitionix.forgeai.api.proxy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InfrastructureProxyTransportTest {

    @Test
    void humanQueryRouteRequestTimeoutUsesConfiguredKnowledgeDeadline() {
        final HttpClient httpClient = mock(HttpClient.class);
        final CompletableFuture<HttpResponse<InputStream>> upstream = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(upstream);

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final ForgeAiHumanQueryProperties humanQueryProperties = new ForgeAiHumanQueryProperties();
        humanQueryProperties.setRequestTimeoutSeconds(42);
        final ObjectMapper objectMapper = new ObjectMapper();
        final InfrastructureProxyTransport transport = new InfrastructureProxyTransport(
                httpClient,
                new InfrastructureProxyRouteRegistry(properties, humanQueryProperties),
                properties,
                new InfrastructureProxyResponseMapper(objectMapper),
                objectMapper
        );
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);

        transport.forward(
                "knowledge.query",
                Map.of(),
                "{\"queryText\":\"A.start\"}".getBytes(UTF_8),
                new HttpHeaders(),
                request
        );

        final ArgumentCaptor<HttpRequest> upstreamRequest = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendAsync(upstreamRequest.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(upstreamRequest.getValue().timeout()).contains(Duration.ofSeconds(42));
    }

    @Test
    void jarvisQueryRouteRequestTimeoutUsesConfiguredHumanQueryDeadline() {
        final HttpClient httpClient = mock(HttpClient.class);
        final CompletableFuture<HttpResponse<InputStream>> upstream = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(upstream);

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final ForgeAiHumanQueryProperties humanQueryProperties = new ForgeAiHumanQueryProperties();
        humanQueryProperties.setRequestTimeoutSeconds(42);
        final ObjectMapper objectMapper = new ObjectMapper();
        final InfrastructureProxyTransport transport = new InfrastructureProxyTransport(
                httpClient,
                new InfrastructureProxyRouteRegistry(properties, humanQueryProperties),
                properties,
                new InfrastructureProxyResponseMapper(objectMapper),
                objectMapper
        );
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);

        transport.forward(
                "jarvis.query",
                Map.of(),
                "{\"queryText\":\"JarvisGateway\"}".getBytes(UTF_8),
                new HttpHeaders(),
                request
        );

        final ArgumentCaptor<HttpRequest> upstreamRequest = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendAsync(upstreamRequest.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(upstreamRequest.getValue().timeout()).contains(Duration.ofSeconds(42));
    }

    @Test
    void jarvisQueryRouteUsesHumanQueryDeadlineForDeterministicResponse() throws Exception {
        final HttpClient httpClient = mock(HttpClient.class);
        final Duration controlledResponseDelay = Duration.ofMillis(150);
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(invocation -> {
                    final HttpRequest request = invocation.getArgument(0);
                    final Duration requestTimeout = request.timeout().orElse(Duration.ZERO);
                    if (requestTimeout.compareTo(controlledResponseDelay) <= 0) {
                        return CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("timed out"));
                    }
                    final HttpResponse<InputStream> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.body()).thenReturn(new ByteArrayInputStream("""
                            {"answerLanguage":"uk","answers":[{"graphId":"graph-1","sources":["source-a"],"queryEntries":[{"unitId":"source-a:unit:JarvisGateway","sourceId":"source-a","root":{"qualifiedName":"JarvisGateway","label":"JarvisGateway"}}],"text":"JarvisGateway handles the request.","complete":true,"diagnostics":[]}],"diagnostics":[]}
                            """.strip().getBytes(UTF_8)));
                    when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (left, right) -> true));
                    return CompletableFuture.completedFuture(response);
                });

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getJarvis().setReadTimeout(Duration.ofMillis(120));
        final ForgeAiHumanQueryProperties humanQueryProperties = new ForgeAiHumanQueryProperties();
        humanQueryProperties.setRequestTimeout(Duration.ofMillis(200));
        final ObjectMapper objectMapper = new ObjectMapper();
        final InfrastructureProxyTransport transport = new InfrastructureProxyTransport(
                httpClient,
                new InfrastructureProxyRouteRegistry(properties, humanQueryProperties),
                properties,
                new InfrastructureProxyResponseMapper(objectMapper),
                objectMapper
        );
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);

        final ResponseEntity<byte[]> response = transport.forward(
                "jarvis.query",
                Map.of(),
                "{\"queryText\":\"JarvisGateway\"}".getBytes(UTF_8),
                new org.springframework.http.HttpHeaders(),
                request
        ).get(1, TimeUnit.SECONDS);

        final String body = new String(response.getBody(), UTF_8);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body).contains("\"answers\"");
        assertThat(body).doesNotContain("\"status\"");
        assertThat(body).doesNotContain("\"flows\"");
        assertThat(body).doesNotContain("UPSTREAM_TIMEOUT");
    }

    @Test
    void humanQueryRouteUsesKnowledgeHumanQueryDeadlineForDeterministicResponse() throws Exception {
        final HttpClient httpClient = mock(HttpClient.class);
        final Duration controlledResponseDelay = Duration.ofMillis(25);
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(invocation -> {
                    final HttpRequest request = invocation.getArgument(0);
                    final Duration requestTimeout = request.timeout().orElse(Duration.ZERO);
                    if (requestTimeout.compareTo(controlledResponseDelay) <= 0) {
                        return CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("timed out"));
                    }
                    final HttpResponse<InputStream> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.body()).thenReturn(new ByteArrayInputStream(
                            "{\"answerLanguage\":\"uk\",\"answers\":[{\"graphId\":\"graph-1\",\"sources\":[\"source-a\"],\"queryEntries\":[{\"unitId\":\"source-a:unit:A.start\",\"sourceId\":\"source-a\",\"root\":{\"qualifiedName\":\"A.start\",\"label\":\"A.start\"}}],\"text\":\"ok\",\"complete\":true,\"diagnostics\":[]}],\"diagnostics\":[]}".getBytes(UTF_8)
                    ));
                    when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (left, right) -> true));
                    return CompletableFuture.completedFuture(response);
                });

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final ForgeAiHumanQueryProperties humanQueryProperties = new ForgeAiHumanQueryProperties();
        humanQueryProperties.setRequestTimeoutSeconds(1);
        final ObjectMapper objectMapper = new ObjectMapper();
        final InfrastructureProxyTransport transport = new InfrastructureProxyTransport(
                httpClient,
                new InfrastructureProxyRouteRegistry(properties, humanQueryProperties),
                properties,
                new InfrastructureProxyResponseMapper(objectMapper),
                objectMapper
        );
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);

        final ResponseEntity<byte[]> response = transport.forward(
                "knowledge.query",
                Map.of(),
                "{\"queryText\":\"A.start\"}".getBytes(UTF_8),
                new org.springframework.http.HttpHeaders(),
                request
        ).get(1, TimeUnit.SECONDS);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final String body = new String(response.getBody(), UTF_8);
        assertThat(body).contains("\"answers\"");
        assertThat(body).doesNotContain("\"status\"");
    }

    @Test
    void jarvisQueryPreservesControlledHumanQueryServerErrorStatusesAndBodies() throws Exception {
        final ObjectMapper objectMapper = new ObjectMapper();
        final Map<Integer, String> cases = Map.of(
                503, "{\"code\":\"KNOWLEDGE_QUERY_FAILED\",\"message\":\"Knowledge query failed before a factual answer could be built.\",\"correlationId\":\"corr-failed\"}",
                504, "{\"code\":\"HUMAN_QUERY_TIMEOUT\",\"message\":\"Knowledge human query timed out.\",\"correlationId\":\"corr-timeout\"}",
                502, "{\"code\":\"QUERY_INTERPRETATION_FAILED\",\"message\":\"The local model could not interpret the query.\",\"correlationId\":\"corr-query\"}"
        );

        for (final Map.Entry<Integer, String> entry : cases.entrySet()) {
            final HttpClient httpClient = mock(HttpClient.class);
            final HttpResponse<InputStream> upstreamResponse = mock(HttpResponse.class);
            when(upstreamResponse.statusCode()).thenReturn(entry.getKey());
            when(upstreamResponse.body()).thenReturn(new ByteArrayInputStream(entry.getValue().getBytes(UTF_8)));
            when(upstreamResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (left, right) -> true));
            when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                    .thenReturn(CompletableFuture.completedFuture(upstreamResponse));

            final ResponseEntity<byte[]> response = this.transport(httpClient).forward(
                    "jarvis.query",
                    Map.of(),
                    "{\"queryText\":\"JarvisGateway\"}".getBytes(UTF_8),
                    new org.springframework.http.HttpHeaders(),
                    requestWithoutQuery()
            ).get(1, TimeUnit.SECONDS);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(entry.getKey()));
            assertThat(objectMapper.readTree(response.getBody())).isEqualTo(objectMapper.readTree(entry.getValue()));
        }
    }

    @Test
    void jarvisQueryTransportFailureRemainsGenericBadGateway() throws Exception {
        final HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(CompletableFuture.failedFuture(new java.net.ConnectException("refused")));

        final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Correlation-Id", "corr-no-response");
        final ResponseEntity<byte[]> response = this.transport(httpClient).forward(
                "jarvis.query",
                Map.of(),
                "{\"queryText\":\"JarvisGateway\"}".getBytes(UTF_8),
                headers,
                requestWithoutQuery()
        ).get(1, TimeUnit.SECONDS);

        final Map<?, ?> body = new ObjectMapper().readValue(response.getBody(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(body.get("code")).isEqualTo("UPSTREAM_ERROR");
        assertThat(body.get("message")).isEqualTo("Jarvis proxy request failed.");
        assertThat(body.get("correlationId")).isEqualTo("corr-no-response");
        assertThat(body.get("route")).isEqualTo("jarvis.query");
    }

    @Test
    void aiRuntimeRoutePassesThroughKnowledgeResponseBodyUnmodified() throws Exception {
        final String upstreamBody = """
                {"providers":[{"providerId":"ollama","displayName":"Ollama","status":"READY","models":[{"modelId":"qwen2.5-coder:14b","displayName":"qwen2.5-coder:14b"}],"version":"0.30.6"}]}
                """.strip();
        final HttpClient httpClient = mock(HttpClient.class);
        final HttpResponse<InputStream> upstreamResponse = mock(HttpResponse.class);
        when(upstreamResponse.statusCode()).thenReturn(200);
        when(upstreamResponse.body()).thenReturn(new ByteArrayInputStream(upstreamBody.getBytes(UTF_8)));
        when(upstreamResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(
                Map.of("Content-Type", List.of("application/json"), "Cache-Control", List.of("no-store")),
                (left, right) -> true
        ));
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(CompletableFuture.completedFuture(upstreamResponse));
        final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Correlation-Id", "corr-ai-runtime");

        final ResponseEntity<byte[]> response = this.transport(httpClient).forward(
                "knowledge.ai-runtime",
                Map.of(),
                null,
                headers,
                requestWithoutQuery()
        ).get(1, TimeUnit.SECONDS);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), UTF_8)).isEqualTo(upstreamBody);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-ai-runtime");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void aiRuntimeRouteMapsKnowledgeServerErrorThroughExistingProxyConvention() throws Exception {
        final HttpClient httpClient = mock(HttpClient.class);
        final HttpResponse<InputStream> upstreamResponse = mock(HttpResponse.class);
        when(upstreamResponse.statusCode()).thenReturn(500);
        when(upstreamResponse.body()).thenReturn(new ByteArrayInputStream("{\"code\":\"FAILED\"}".getBytes(UTF_8)));
        when(upstreamResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (left, right) -> true));
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(CompletableFuture.completedFuture(upstreamResponse));

        final ResponseEntity<byte[]> response = this.transport(httpClient).forward(
                "knowledge.ai-runtime",
                Map.of(),
                null,
                new org.springframework.http.HttpHeaders(),
                requestWithoutQuery()
        ).get(1, TimeUnit.SECONDS);

        final Map<?, ?> body = new ObjectMapper().readValue(response.getBody(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(body.get("code")).isEqualTo("UPSTREAM_ERROR");
        assertThat(body.get("route")).isEqualTo("knowledge.ai-runtime");
    }

    @Test
    void aiRuntimeRouteMapsKnowledgeTimeoutThroughExistingProxyConvention() throws Exception {
        final HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("timed out")));

        final ResponseEntity<byte[]> response = this.transport(httpClient).forward(
                "knowledge.ai-runtime",
                Map.of(),
                null,
                new org.springframework.http.HttpHeaders(),
                requestWithoutQuery()
        ).get(1, TimeUnit.SECONDS);

        final Map<?, ?> body = new ObjectMapper().readValue(response.getBody(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(body.get("code")).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(body.get("route")).isEqualTo("knowledge.ai-runtime");
    }

    private InfrastructureProxyTransport transport(final HttpClient httpClient) {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final ForgeAiHumanQueryProperties humanQueryProperties = new ForgeAiHumanQueryProperties();
        humanQueryProperties.setRequestTimeoutSeconds(42);
        final ObjectMapper objectMapper = new ObjectMapper();
        return new InfrastructureProxyTransport(
                httpClient,
                new InfrastructureProxyRouteRegistry(properties, humanQueryProperties),
                properties,
                new InfrastructureProxyResponseMapper(objectMapper),
                objectMapper
        );
    }

    private static HttpServletRequest requestWithoutQuery() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);
        return request;
    }
}
