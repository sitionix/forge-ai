package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpCredentialSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SdkMcpRemoteClientProbeTest {
    @Test void initializesAndCollectsAllToolPagesWithoutCallingTools() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var pages = new AtomicInteger();
        var json = new ObjectMapper();
        server.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.isBlank()) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            var message = json.readTree(request);
            String method = message.path("method").asText();
            String id = json.writeValueAsString(message.get("id"));
            String response;
            int status = 200;
            if (method.equals("initialize")) {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
                response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}";
            } else if (method.equals("tools/list")) {
                if (pages.getAndIncrement() == 0) response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"read\",\"description\":\"Read data\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"page-2\"}}";
                else response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"write\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}]}}";
            } else if (method.equals("notifications/initialized")) {
                status = 202; response = "";
            } else { exchange.sendResponseHeaders(400, -1); exchange.close(); return; }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            if (status == 200) exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(2), Duration.ofSeconds(3), 65536, 4, 10,
                    Set.of("127.0.0.1:" + port));
            var report = probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.BEARER,
                    "synthetic-token".getBytes(StandardCharsets.UTF_8));
            assertThat(report.protocolVersion()).isEqualTo("2025-11-25");
            assertThat(report.tools()).extracting("name").containsExactly("read", "write");
            assertThat(report.tools().getFirst().schemaFingerprint()).startsWith("sha256:");
            assertThat(report.tools().get(1).schemaFingerprint()).isNotEqualTo(report.tools().getFirst().schemaFingerprint());
            assertThat(pages.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test void rejectsPrivateAddressWithoutExplicitHostPortAllowance() {
        var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(1), 65536, 4, 10, Set.of());
        for (String endpoint : new String[]{"http://127.0.0.1:9999/mcp", "http://[::1]/mcp",
                "http://169.254.169.254/mcp", "http://[fe80::1]/mcp", "https://user:pass@example.org/mcp",
                "https://example.org/mcp?token=secret", "file:///tmp/mcp"}) {
            assertThatThrownBy(() -> probe.probe(URI.create(endpoint), McpAuthType.NONE, null))
                    .as(endpoint).isInstanceOf(McpProbeException.class).extracting("reason")
                    .isEqualTo(McpProbeException.Reason.ENDPOINT_DENIED);
        }
    }

    @Test void preservesAuthenticationAndPermissionFailures() throws Exception {
        for (int status : new int[]{401, 403}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(exchange.getRequestMethod().equals("GET") ? 405 : status, -1);
                exchange.close();
            });
            server.start();
            try {
                int port = server.getAddress().getPort();
                var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                        Set.of("127.0.0.1:" + port));
                assertThatThrownBy(() -> probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE, null))
                        .isInstanceOf(McpProbeException.class).extracting("reason")
                        .isEqualTo(status == 401 ? McpProbeException.Reason.AUTH_REQUIRED : McpProbeException.Reason.FORBIDDEN);
            } finally { server.stop(0); }
        }
    }

    @Test void malformedJsonIsInvalidResponseRatherThanSuccessfulEmptyInventory() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] malformed = "{malformed-canary".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, malformed.length);
                try (var out = exchange.getResponseBody()) { out.write(malformed); }
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE, null))
                    .isInstanceOf(McpProbeException.class).extracting("reason")
                    .isEqualTo(McpProbeException.Reason.INVALID_RESPONSE);
        } finally { server.stop(0); }
    }

    @Test void unsupportedProtocolIsNotReportedAsReady() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.isBlank()) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            var id = json.writeValueAsString(json.readTree(request).get("id"));
            String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2099-01-01\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE, null))
                    .isInstanceOf(McpProbeException.class).extracting("reason")
                    .isEqualTo(McpProbeException.Reason.UNSUPPORTED_PROTOCOL);
        } finally { server.stop(0); }
    }

    @Test void redirectNeverForwardsCredentialToAdvertisedTarget() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var targetCalls = new AtomicInteger();
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"),
                    McpAuthType.BEARER, "synthetic-redirect-secret".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(McpProbeException.class);
            assertThat(targetCalls.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test void secretHeaderIsScopedToMcpEndpoint() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.isBlank()) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            assertThat(exchange.getRequestHeaders().getFirst("X-Synthetic-Key")).isEqualTo("synthetic-header-secret");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            var message = json.readTree(request);
            if (!message.path("method").asText().equals("initialize")) {
                if (message.path("method").asText().equals("notifications/initialized")) {
                    exchange.sendResponseHeaders(202, -1); exchange.close(); return;
                }
                String id = json.writeValueAsString(message.get("id"));
                byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[]}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
                return;
            }
            String id = json.writeValueAsString(message.get("id"));
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            var report = probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"),
                    McpAuthType.SECRET_HEADERS, McpCredentialSecret.headers(Map.of("X-Synthetic-Key", "synthetic-header-secret")).bytes());
            assertThat(report.tools()).isEmpty();
        } finally { server.stop(0); }
    }

    @Test void oversizedResponseIsRejectedWithSafeInvalidResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
            } else {
                byte[] body = ("{\"payload\":\"" + "x".repeat(2048) + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE, null))
                    .isInstanceOf(McpProbeException.class).extracting("reason")
                    .isEqualTo(McpProbeException.Reason.INVALID_RESPONSE);
        } finally { server.stop(0); }
    }

    @Test void sdkAcceptsBoundedEventStreamResponseWithoutCustomParser() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.isBlank()) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            var message = json.readTree(request);
            String method = message.path("method").asText();
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1); exchange.close(); return;
            }
            String id = json.writeValueAsString(message.get("id"));
            String result = method.equals("initialize")
                    ? "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}"
                    : "{\"tools\":[]}";
            String data = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}\n\n";
            byte[] body = data.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThat(probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE, null).tools()).isEmpty();
        } finally { server.stop(0); }
    }

    @Test void duplicateToolNamesFailInsteadOfSilentlyReplacingInventory() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.isBlank()) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            var message = json.readTree(request);
            String method = message.path("method").asText();
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1); exchange.close(); return;
            }
            String id = json.writeValueAsString(message.get("id"));
            String result = method.equals("initialize")
                    ? "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}"
                    : "{\"tools\":[{\"name\":\"same\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"same\",\"inputSchema\":{\"type\":\"object\"}}]}";
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var probe = new SdkMcpRemoteClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 65536, 2, 10,
                    Set.of("127.0.0.1:" + port));
            assertThatThrownBy(() -> probe.probe(URI.create("http://127.0.0.1:" + port + "/mcp"), McpAuthType.NONE, null))
                    .isInstanceOf(McpProbeException.class).extracting("reason")
                    .isEqualTo(McpProbeException.Reason.INVALID_RESPONSE);
        } finally { server.stop(0); }
    }
}
