package com.sitionix.forgeai.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.JarvisKnowledgeQueryIntent;
import com.sitionix.forgeai.api.JarvisKnowledgeQueryRequest;
import com.sitionix.forgeai.api.JarvisKnowledgeQueryResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class InfrastructureProxyTransportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void forwardJsonSerializesRequestAndDeserializesTypedSuccessResponse() throws Exception {
        final AtomicReference<String> capturedBody = new AtomicReference<>();
        try (TestServer server = TestServer.start("/api/v1/jarvis/query", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.getResponseHeaders().add("ETag", "\"rev-1\"");
            exchange.getResponseHeaders().add("X-Graph-Revision", "graph-rev-1");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.getResponseHeaders().add("Server-Timing", "upstream;dur=2");
            send(exchange, 200, """
                    {"queryId":"q1","status":"OK","intent":"UNKNOWN","matchedSources":[],"matchedNodes":[{"sourceId":"source-a"}],"flowPaths":[],"nodes":[],"edges":[],"verifiedPaths":[],"evidence":[],"unresolved":[],"external":[],"coverage":{"matchedNodeCount":1},"diagnostics":[]}
                    """);
        })) {
            final InfrastructureProxyTransport transport = this.transport(server);
            final HttpHeaders headers = new HttpHeaders();
            headers.set("X-Correlation-Id", "corr-1");

            final ResponseEntity<?> response = transport.forwardJson(
                    "jarvis.query",
                    Map.of(),
                    new JarvisKnowledgeQueryRequest("JarvisGateway", JarvisKnowledgeQueryIntent.UNKNOWN, "en", false, 10),
                    JarvisKnowledgeQueryResponse.class,
                    headers,
                    this.request()
            ).join();

            final JsonNode forwarded = this.objectMapper.readTree(capturedBody.get());
            assertThat(forwarded.get("queryText").asText()).isEqualTo("JarvisGateway");
            assertThat(forwarded.has("query")).isFalse();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(JarvisKnowledgeQueryResponse.class);
            final JarvisKnowledgeQueryResponse body = (JarvisKnowledgeQueryResponse) response.getBody();
            assertThat(body.queryId()).isEqualTo("q1");
            assertThat(body.matchedNodes().get(0).get("sourceId").asText()).isEqualTo("source-a");
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-1");
            assertThat(response.getHeaders().getFirst("ETag")).isEqualTo("\"rev-1\"");
            assertThat(response.getHeaders().getFirst("X-Graph-Revision")).isEqualTo("graph-rev-1");
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeaders().getFirst("Server-Timing")).isEqualTo("upstream;dur=2");
            assertThat(response.getHeaders().getFirst("X-Proxy-Duration-Ms")).isNotBlank();
        }
    }

    @Test
    void forwardJsonMapsNonJsonUpstreamResponseToProxyErrorObject() throws Exception {
        try (TestServer server = TestServer.start("/api/v1/jarvis/status", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", MediaType.TEXT_PLAIN_VALUE);
            send(exchange, 200, "not json");
        })) {
            final ResponseEntity<?> response = this.transport(server).forwardJson(
                    "jarvis.status",
                    Map.of(),
                    null,
                    JsonNode.class,
                    new HttpHeaders(),
                    this.request()
            ).join();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isInstanceOf(InfrastructureProxyErrorResponse.class);
            final InfrastructureProxyErrorResponse body = (InfrastructureProxyErrorResponse) response.getBody();
            assertThat(body.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
            assertThat(body.route()).isEqualTo("jarvis.status");
            assertThat(response.getHeaders().getFirst("X-Proxy-Error-Source")).isEqualTo("upstream");
        }
    }

    @Test
    void forwardJsonMapsOversizedRequestBodyToProxyErrorObject() throws Exception {
        try (TestServer server = TestServer.start("/api/v1/jarvis/command", exchange -> send(exchange, 200, "{}"))) {
            final InfrastructureProxyProperties properties = this.properties(server);
            properties.getProxy().setMaxRequestBodyBytes(4);
            final InfrastructureProxyTransport transport = this.transport(properties);

            final ResponseEntity<?> response = transport.forwardJson(
                    "jarvis.command",
                    Map.of(),
                    this.objectMapper.readTree("{\"long\":\"body\"}"),
                    JsonNode.class,
                    new HttpHeaders(),
                    this.request()
            ).join();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(response.getBody()).isInstanceOf(InfrastructureProxyErrorResponse.class);
            final InfrastructureProxyErrorResponse body = (InfrastructureProxyErrorResponse) response.getBody();
            assertThat(body.code()).isEqualTo("REQUEST_BODY_TOO_LARGE");
            assertThat(body.route()).isEqualTo("jarvis.command");
        }
    }

    private InfrastructureProxyTransport transport(final TestServer server) {
        return this.transport(this.properties(server));
    }

    private InfrastructureProxyTransport transport(final InfrastructureProxyProperties properties) {
        return new InfrastructureProxyTransport(
                HttpClient.newHttpClient(),
                new InfrastructureProxyRouteRegistry(),
                properties,
                new InfrastructureProxyResponseMapper(),
                this.objectMapper
        );
    }

    private InfrastructureProxyProperties properties(final TestServer server) {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getJarvis().setBaseUrl(server.baseUri());
        properties.getKnowledge().setBaseUrl(server.baseUri());
        return properties;
    }

    private HttpServletRequest request() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);
        return request;
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record TestServer(HttpServer server) implements AutoCloseable {

        static TestServer start(final String path, final ThrowingExchangeHandler handler) throws IOException {
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> {
                try {
                    handler.handle(exchange);
                } catch (final Exception exception) {
                    send(exchange, 500, "{\"error\":\"test failure\"}");
                }
            });
            server.start();
            return new TestServer(server);
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }

    @FunctionalInterface
    private interface ThrowingExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
