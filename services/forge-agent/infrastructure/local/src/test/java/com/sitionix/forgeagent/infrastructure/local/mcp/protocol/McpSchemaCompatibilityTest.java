package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.exception.McpToolCallException;
import com.sitionix.forgeagent.domain.model.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Exercises the wire boundary, including the schema exposed to native agents. */
class McpSchemaCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":{},
             "$schema":"https://json-schema.org/draft/2020-12/schema",
             "$defs":{"query":{"type":"string","minLength":1}},
             "properties":{"query":{"$ref":"#/$defs/query"}},
             "required":["query"],"allOf":[{"maxProperties":2}],
             "unevaluatedProperties":false,"x-provider-extension":{"version":1}}
            """;

    @Test void preservesCompleteSchemasForProbeNativeDiscoveryAndCallOverJsonAndSse() throws Exception {
        for (boolean sse : new boolean[]{false, true}) {
            try (var fixture = new Fixture(SCHEMA, sse)) {
                var report = fixture.client.probe(fixture.endpoint, McpAuthType.NONE, null);
                var approval = new McpAllowedTool("search", report.tools().getFirst().schemaFingerprint());
                var tools = fixture.client.discoverApprovedTools(fixture.endpoint, McpAuthType.NONE, null,
                        Set.of(approval));
                var wire = JSON.readTree(JSON.writeValueAsBytes(tools.getFirst()));
                assertThat(wire.get("inputSchema")).isEqualTo(JSON.readTree(SCHEMA));
                var result = fixture.client.call(fixture.endpoint, McpAuthType.NONE, null,
                        "search", approval.schemaFingerprint(), "{\"query\":\"test\"}");
                assertThat(result.isError()).isFalse();
                assertThat(result.contentJson()).contains("fixture-result");
                assertThat(fixture.calls.get()).isEqualTo(1);
                // Previously discarded schema keywords must participate in approval checks.
                fixture.schema = SCHEMA.replace("\"version\":1", "\"version\":2");
                assertThatThrownBy(() -> fixture.client.call(fixture.endpoint, McpAuthType.NONE, null,
                        "search", approval.schemaFingerprint(), "{\"query\":\"test\"}"))
                        .isInstanceOf(McpToolCallException.class).extracting("kind")
                        .isEqualTo(McpToolCallException.Kind.SCHEMA_CHANGED);
                assertThat(fixture.calls.get()).isEqualTo(1);
            }
        }
    }

    @Test void acceptsBooleanAndConstrainedAdditionalPropertiesWithoutChangingTheirMeaning() throws Exception {
        for (String additional : new String[]{"true", "false", "{}", "{\"type\":\"string\"}"}) {
            String schema = "{\"type\":\"object\",\"additionalProperties\":" + additional + "}";
            try (var fixture = new Fixture(schema, false)) {
                var report = fixture.client.probe(fixture.endpoint, McpAuthType.NONE, null);
                var tools = fixture.client.discoverApprovedTools(fixture.endpoint, McpAuthType.NONE, null,
                        Set.of(new McpAllowedTool("search", report.tools().getFirst().schemaFingerprint())));
                assertThat(JSON.readTree(JSON.writeValueAsBytes(tools.getFirst())).get("inputSchema"))
                        .isEqualTo(JSON.readTree(schema));
            }
        }
    }

    @Test void malformedSchemaIsInvalidResponseInsteadOfUnavailable() throws Exception {
        for (String schema : new String[]{"42", "null", "{}", "[]", "{\"type\":\"string\"}"}) {
            try (var fixture = new Fixture(schema, false)) {
                assertThatThrownBy(() -> fixture.client.probe(fixture.endpoint, McpAuthType.NONE, null))
                        .as("schema %s", schema)
                        .isInstanceOf(McpProbeException.class).extracting("reason")
                        .isEqualTo(McpProbeException.Reason.INVALID_RESPONSE);
            }
        }
    }

    @Test void schemaObjectKeyOrderingDoesNotInvalidateApproval() throws Exception {
        try (var fixture = new Fixture("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\",\"minLength\":1}}}", false)) {
            String fingerprint = fixture.client.probe(fixture.endpoint, McpAuthType.NONE, null)
                    .tools().getFirst().schemaFingerprint();
            fixture.schema = "{\"properties\":{\"q\":{\"minLength\":1,\"type\":\"string\"}},\"type\":\"object\"}";
            assertThat(fixture.client.probe(fixture.endpoint, McpAuthType.NONE, null)
                    .tools().getFirst().schemaFingerprint()).isEqualTo(fingerprint);
        }
    }

    @Test void exactDecimalConstraintsSurviveDiscoveryAndInvalidateApprovalWhenChanged() throws Exception {
        for (boolean sse : new boolean[]{false, true}) {
            String schema = """
                    {"type":"object","properties":{"n":{"type":"number","maximum":0.123456789012345678901}},
                     "x-null":null,"x-large-integer":123456789012345678901234567890}
                    """;
            try (var fixture = new Fixture(schema, sse)) {
                String fingerprint = fixture.client.probe(fixture.endpoint, McpAuthType.NONE, null)
                        .tools().getFirst().schemaFingerprint();
                var tools = fixture.client.discoverApprovedTools(fixture.endpoint, McpAuthType.NONE, null,
                        Set.of(new McpAllowedTool("search", fingerprint)));
                var preserved = JSON.readTree(JSON.writeValueAsBytes(tools.getFirst())).get("inputSchema");
                // Inspect serialized digits rather than parsing them through a second lossy mapper.
                String wire = JSON.writeValueAsString(tools.getFirst());
                assertThat(wire).contains("0.123456789012345678901", "123456789012345678901234567890");
                assertThat(preserved.has("x-null")).isTrue();
                assertThat(preserved.get("x-null").isNull()).isTrue();
                fixture.schema = schema.replace("0.123456789012345678901", "0.123456789012345678902");
                assertThatThrownBy(() -> fixture.client.call(fixture.endpoint, McpAuthType.NONE, null,
                        "search", fingerprint, "{\"n\":0}"))
                        .isInstanceOf(McpToolCallException.class).extracting("kind")
                        .isEqualTo(McpToolCallException.Kind.SCHEMA_CHANGED);
                assertThat(fixture.calls.get()).isZero();
            }
        }
    }

    @Test void remoteStructuredOutputWithProviderReferencesIsRelayedWithoutResolution() throws Exception {
        try (var fixture = new Fixture("{\"type\":\"object\"}", false)) {
            fixture.outputSchema = "{\"type\":\"object\",\"properties\":{\"q\":{\"$ref\":\""
                    + fixture.endpoint.resolve("/schema") + "\"}}}";
            fixture.toolResult = "{\"content\":[],\"structuredContent\":{\"q\":\"value\"},\"isError\":false}";
            String fingerprint = fixture.client.probe(fixture.endpoint, McpAuthType.NONE, null)
                    .tools().getFirst().schemaFingerprint();
            var result = fixture.client.call(fixture.endpoint, McpAuthType.NONE, null,
                    "search", fingerprint, "{}");
            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContentJson()).isEqualTo("{\"q\":\"value\"}");
            assertThat(fixture.calls.get()).isEqualTo(1);
            assertThat(fixture.referenceFetches.get()).isZero();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final URI endpoint;
        final SdkMcpRemoteClient client;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger referenceFetches = new AtomicInteger();
        volatile String schema;
        volatile String outputSchema;
        volatile String toolResult = "{\"content\":[{\"type\":\"text\",\"text\":\"fixture-result\"}],\"isError\":false}";

        Fixture(String schema, boolean sse) throws Exception {
            this.schema = schema;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/schema", exchange -> {
                referenceFetches.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close();
            });
            server.createContext("/mcp", exchange -> {
                try {
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    if (request.length == 0) { exchange.sendResponseHeaders(405, -1); return; }
                    var message = JSON.readTree(request);
                    String method = message.path("method").asText();
                    if (method.equals("notifications/initialized")) {
                        exchange.sendResponseHeaders(202, -1); return;
                    }
                    String result = switch (method) {
                        case "initialize" -> "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                        case "tools/list" -> "{\"tools\":[{\"name\":\"search\",\"inputSchema\":" + this.schema
                                + (outputSchema == null ? "" : ",\"outputSchema\":" + outputSchema) + "}]}";
                        case "tools/call" -> {
                            calls.incrementAndGet();
                            yield toolResult;
                        }
                        default -> throw new IllegalStateException("Unexpected method: " + method);
                    };
                    String response = "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id") + ",\"result\":" + result + "}";
                    if (sse) response = "event: message\ndata: " + JSON.writeValueAsString(JSON.readTree(response)) + "\n\n";
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            server.start();
            int port = server.getAddress().getPort();
            endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
            client = new SdkMcpRemoteClient(Duration.ofSeconds(2), Duration.ofSeconds(3), 65536, 4, 10,
                    Set.of("127.0.0.1:" + port));
        }

        @Override public void close() { server.stop(0); }
    }
}
