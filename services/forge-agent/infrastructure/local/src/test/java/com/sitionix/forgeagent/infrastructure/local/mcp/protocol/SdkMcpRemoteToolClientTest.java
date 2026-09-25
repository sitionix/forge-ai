package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.exception.McpToolCallException;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SdkMcpRemoteToolClientTest {
    private static final String SCHEMA = "sha256:a2c799262a3ce3c19ef5cdd983bf3d12b43ab3c426227091b909dcb7054738c0";
    private final ObjectMapper json = new ObjectMapper();

    @Test void callPreservesStructuredResultAndDoesNotFlattenToolError() throws Exception {
        for (boolean toolError : new boolean[]{false, true}) {
            List<String> methods = Collections.synchronizedList(new ArrayList<>());
            var server = fixture(methods, toolError, false);
            try {
                int port = server.getAddress().getPort();
                var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                        Set.of("127.0.0.1:" + port));
                var result = client.call(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE,
                        null, "search", SCHEMA, "{\"query\":\"hello\"}");
                assertThat(result.isError()).isEqualTo(toolError);
                assertThat(json.readTree(result.contentJson()).get(0).path("text").asText()).isEqualTo("answer");
                assertThat(json.readTree(result.structuredContentJson()).path("rows").get(0).asText()).isEqualTo("answer");
                assertThat(methods).containsExactly("initialize", "notifications/initialized", "tools/list", "tools/call");
            } finally { server.stop(0); }
        }
    }

    @Test void protocolErrorIsNotAResultOrToolIsError() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, true);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> client.call(URI.create("http://127.0.0.1:" + port + "/mcp"),
                    McpAuthType.NONE, null, "search", SCHEMA, "{}"))
                    .isInstanceOf(McpToolCallException.class)
                    .extracting("kind", "protocolCode")
                    .containsExactly(McpToolCallException.Kind.PROTOCOL_FAILURE, -32001);
            assertThat(methods).contains("tools/call");
        } finally { server.stop(0); }
    }

    @Test void changedSchemaRejectsBeforeToolCall() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, false);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> client.call(URI.create("http://127.0.0.1:" + port + "/mcp"),
                    McpAuthType.NONE, null, "search", "sha256:" + "a".repeat(64), "{}"))
                    .isInstanceOf(McpToolCallException.class)
                    .extracting("kind").isEqualTo(McpToolCallException.Kind.SCHEMA_CHANGED);
            assertThat(methods).doesNotContain("tools/call");
        } finally { server.stop(0); }
    }

    @Test void revokedDuringDiscoveryCannotDispatchToolCall() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, false);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> client.call(URI.create("http://127.0.0.1:" + port + "/mcp"),
                    McpAuthType.NONE, null, "search", SCHEMA, "{}", () -> false))
                    .isInstanceOf(McpProbeException.class)
                    .extracting("reason").isEqualTo(McpProbeException.Reason.UNAVAILABLE);
            assertThat(methods).contains("tools/list").doesNotContain("tools/call");
        } finally { server.stop(0); }
    }

    @Test void toolCallCanOutliveTheShortProbeRequestTimeout() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, false, 1200);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(1), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port), null, new ObjectMapper(), Duration.ofSeconds(3));
            var result = client.call(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE,
                    null, "search", SCHEMA, "{\"query\":\"hello\"}");
            assertThat(result.isError()).isFalse();
            assertThat(methods).contains("tools/call");
        } finally { server.stop(0); }
    }

    @Test void toolCallTimeoutIsUnavailableRatherThanMalformedProtocol() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, false, 1000);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port), null, new ObjectMapper(), Duration.ofMillis(400));
            assertThatThrownBy(() -> client.call(URI.create("http://127.0.0.1:" + port + "/mcp"),
                    McpAuthType.NONE, null, "search", SCHEMA, "{\"query\":\"hello\"}"))
                    .isInstanceOf(McpProbeException.class)
                    .extracting("reason").isEqualTo(McpProbeException.Reason.UNAVAILABLE);
            assertThat(methods).contains("tools/call");
        } finally { server.stop(0); }
    }

    @Test void discoveryAndToolCallShareOneDeadline() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, false, 650, 650);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port), null, new ObjectMapper(), Duration.ofSeconds(1));
            assertThatThrownBy(() -> client.call(URI.create("http://127.0.0.1:" + port + "/mcp"),
                    McpAuthType.NONE, null, "search", SCHEMA, "{\"query\":\"hello\"}"))
                    .isInstanceOf(McpProbeException.class)
                    .extracting("reason").isEqualTo(McpProbeException.Reason.UNAVAILABLE);
            assertThat(methods).contains("tools/list", "tools/call");
        } finally { server.stop(0); }
    }

    @Test void responseLimitDoesNotRestrictValidToolArguments() throws Exception {
        String arguments = json.writeValueAsString(java.util.Map.of("query", "x".repeat(1500)));
        assertThat(arguments.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(1024);
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, false, 0, 0, arguments);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, 2, 10,
                    Set.of("127.0.0.1:" + port));
            var result = client.call(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE,
                    null, "search", SCHEMA, arguments);
            assertThat(result.isError()).isFalse();
            assertThat(methods).contains("tools/call");
        } finally { server.stop(0); }
    }

    @Test void onlyOneCompleteJsonObjectIsAcceptedAsArguments() throws Exception {
        for (String arguments : new String[] {"{}", "{\"x\":1}"}) {
            List<String> methods = Collections.synchronizedList(new ArrayList<>());
            var server = fixture(methods, false, false, 0, 0, arguments);
            try {
                int port = server.getAddress().getPort();
                var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                        Set.of("127.0.0.1:" + port));
                client.call(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE,
                        null, "search", SCHEMA, arguments);
                assertThat(methods).contains("tools/call");
            } finally { server.stop(0); }
        }

        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        var server = fixture(methods, false, false);
        try {
            int port = server.getAddress().getPort();
            var client = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            for (String arguments : new String[] {"[]", "\"foo\"", "[]{}", "{}{}", "{\"x\":1} trailing"}) {
                assertThatThrownBy(() -> client.call(URI.create("http://127.0.0.1:" + port + "/mcp"),
                        McpAuthType.NONE, null, "search", SCHEMA, arguments))
                        .as("arguments: %s", arguments).isInstanceOf(IllegalArgumentException.class);
                assertThat(methods).doesNotContain("tools/call");
            }
        } finally { server.stop(0); }
    }

    private HttpServer fixture(List<String> methods, boolean toolError, boolean protocolError) throws Exception {
        return fixture(methods, toolError, protocolError, 0);
    }

    private HttpServer fixture(List<String> methods, boolean toolError, boolean protocolError,
                               long callDelayMillis) throws Exception {
        return fixture(methods, toolError, protocolError, 0, callDelayMillis);
    }

    private HttpServer fixture(List<String> methods, boolean toolError, boolean protocolError,
                               long listDelayMillis, long callDelayMillis) throws Exception {
        return fixture(methods, toolError, protocolError, listDelayMillis, callDelayMillis, "{\"query\":\"hello\"}");
    }

    private HttpServer fixture(List<String> methods, boolean toolError, boolean protocolError,
                               long listDelayMillis, long callDelayMillis, String expectedArguments) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (request.isBlank()) { exchange.sendResponseHeaders(405, -1); return; }
                var message = json.readTree(request);
                String method = message.path("method").asText();
                methods.add(method);
                String id = message.has("id") ? json.writeValueAsString(message.get("id")) : null;
                if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1); return; }
                String result = switch (method) {
                    case "initialize" -> "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                    case "tools/list" -> {
                        if (listDelayMillis > 0) {
                            try { Thread.sleep(listDelayMillis); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        }
                        yield "{\"tools\":[{\"name\":\"search\",\"inputSchema\":{\"type\":\"object\"}}]}";
                    }
                    case "tools/call" -> {
                        assertThat(message.path("params").path("name").asText()).isEqualTo("search");
                        if (!protocolError)
                            assertThat(message.path("params").path("arguments")).isEqualTo(json.readTree(expectedArguments));
                        if (callDelayMillis > 0) {
                            try { Thread.sleep(callDelayMillis); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        }
                        yield "{\"content\":[{\"type\":\"text\",\"text\":\"answer\"}],\"structuredContent\":{\"rows\":[\"answer\"]},\"isError\":" + toolError + "}";
                    }
                    default -> throw new IllegalStateException("Unexpected MCP method: " + method);
                };
                String response = protocolError && method.equals("tools/call")
                        ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32001,\"message\":\"synthetic protocol failure\"}}"
                        : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        return server;
    }
}
