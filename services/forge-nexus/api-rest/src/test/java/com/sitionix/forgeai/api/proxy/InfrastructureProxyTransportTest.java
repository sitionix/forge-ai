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
    void humanQueryRouteRequestTimeoutUsesConfiguredKnowledgeDeadlinePlusTransportGrace() {
        final HttpClient httpClient = mock(HttpClient.class);
        final CompletableFuture<HttpResponse<InputStream>> upstream = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(upstream);

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeHumanQueryTransportGrace(Duration.ofSeconds(7));
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
        assertThat(upstreamRequest.getValue().timeout()).contains(Duration.ofSeconds(49));
    }

    @Test
    void jarvisQueryRouteRequestTimeoutExceedsKnowledgeHumanQueryTimeout() {
        final HttpClient httpClient = mock(HttpClient.class);
        final CompletableFuture<HttpResponse<InputStream>> upstream = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(upstream);

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeHumanQueryTransportGrace(Duration.ofSeconds(7));
        properties.getProxy().setJarvisQueryTransportGrace(Duration.ofSeconds(11));
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
        assertThat(upstreamRequest.getValue().timeout()).contains(Duration.ofSeconds(60));
    }

    @Test
    void jarvisQueryRouteDoesNotTimeoutBeforeHumanFlowResponse() throws Exception {
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
                            {"answerLanguage":"uk","answers":[{"source":"source-a","entrypoint":"JarvisGateway","text":"JarvisGateway handles the request."}],"diagnostics":[]}
                            """.strip().getBytes(UTF_8)));
                    when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (left, right) -> true));
                    return CompletableFuture.completedFuture(response);
                });

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getJarvis().setReadTimeout(Duration.ofMillis(120));
        properties.getProxy().setKnowledgeHumanQueryTransportGrace(Duration.ofMillis(50));
        properties.getProxy().setJarvisQueryTransportGrace(Duration.ofMillis(50));
        final ForgeAiHumanQueryProperties humanQueryProperties = new ForgeAiHumanQueryProperties();
        humanQueryProperties.setRequestTimeout(Duration.ofMillis(100));
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
    void humanQueryRouteDoesNotTimeoutBeforeKnowledgeControlledDeadlineResponse() throws Exception {
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
                            "{\"answerLanguage\":\"uk\",\"answers\":[{\"source\":\"source-a\",\"entrypoint\":\"A.start\",\"text\":\"ok\"}],\"diagnostics\":[]}".getBytes(UTF_8)
                    ));
                    when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (left, right) -> true));
                    return CompletableFuture.completedFuture(response);
                });

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeHumanQueryTransportGrace(Duration.ofMillis(20));
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
                503, "{\"code\":\"HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED\",\"message\":\"The complete grounded flow exceeds the available model context.\",\"correlationId\":\"corr-budget\"}",
                504, "{\"code\":\"HUMAN_QUERY_TIMEOUT\",\"message\":\"Knowledge human query timed out.\",\"correlationId\":\"corr-timeout\"}",
                502, "{\"code\":\"HUMAN_ANSWER_GENERATION_FAILED\",\"message\":\"The local model could not produce any grounded flow answers.\",\"correlationId\":\"corr-generation\"}"
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
