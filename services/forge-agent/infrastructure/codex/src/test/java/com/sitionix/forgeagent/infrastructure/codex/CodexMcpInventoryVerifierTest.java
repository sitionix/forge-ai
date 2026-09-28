package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpExecutionSelection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexMcpInventoryVerifierTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CodexMcpInventoryVerifier verifier = new CodexMcpInventoryVerifier(json);
    private final String alias = "forge_0123456789ab4cde80123456789abcde";
    private final McpExecutionSelection selected = new McpExecutionSelection(List.of(
            new McpExecutionSelection.Entry(alias, UUID.fromString("01234567-89ab-4cde-8012-3456789abcde"),
                    "Search", Set.of(new McpAllowedTool("search", "sha256:fingerprint")))), List.of());

    @Test void exactInventoryIsEffective() throws Exception {
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var request = harness.request();
            assertThat(request.path("method").asText()).isEqualTo("mcpServerStatus/list");
            assertThat(request.path("params").path("threadId").asText()).isEqualTo("thread-a");
            assertThat(request.path("params").path("limit").asInt()).isEqualTo(100);
            harness.reply(request, "{\"data\":[" + connected(alias, "search") + "],\"nextCursor\":null}");
            assertThat(result.get(1, TimeUnit.SECONDS).effectiveTools().get(alias)).containsExactly("search");
        }
    }

    @Test void extraServerOnSecondPageFailsClosed() throws Exception {
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var first = harness.request();
            harness.reply(first, "{\"data\":[" + connected(alias, "search") + "],\"nextCursor\":\"next\"}");
            var second = harness.request();
            assertThat(second.path("params").path("cursor").asText()).isEqualTo("next");
            harness.reply(second, "{\"data\":[" + connected("unapproved", "danger") + "],\"nextCursor\":null}");
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Codex MCP inventory did not match issued grants");
        }
    }

    @Test void brokenExpectedConnectionHasOnlySafeDiagnostic() throws Exception {
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var request = harness.request();
            harness.reply(request, "{\"data\":[{\"name\":\"" + alias + "\",\"runtimeStatus\":\"failed\","
                    + "\"tools\":{},\"toolsError\":\"synthetic-secret-canary\"}],\"nextCursor\":null}");
            var inventory = result.get(1, TimeUnit.SECONDS);
            assertThat(inventory.effectiveTools()).isEmpty();
            assertThat(inventory.diagnostics().toString()).doesNotContain("synthetic-secret-canary");
        }
    }

    @Test void emptySelectionRequiresEmptyInventory() throws Exception {
        try (var harness = new Harness()) {
            var empty = new McpExecutionSelection(List.of(), List.of());
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    empty, Duration.ofSeconds(1)));
            var request = harness.request();
            harness.reply(request, "{\"data\":[],\"nextCursor\":null}");
            assertThat(result.get(1, TimeUnit.SECONDS).effectiveTools()).isEmpty();
        }
    }

    @Test void unexpectedOrMissingApprovedToolFailsClosed() throws Exception {
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var request = harness.request();
            harness.reply(request, "{\"data\":[" + connected(alias, "danger") + "],\"nextCursor\":null}");
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Codex MCP inventory did not match issued grants");
        }
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var request = harness.request();
            harness.reply(request, "{\"data\":[{\"name\":\"" + alias
                    + "\",\"runtimeStatus\":\"connected\",\"tools\":{}}],\"nextCursor\":null}");
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Codex MCP inventory did not match issued grants");
        }
    }

    @Test void repeatedCursorAndMalformedPageFailWithFixedMessage() throws Exception {
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var first = harness.request();
            harness.reply(first, "{\"data\":[],\"nextCursor\":\"same\"}");
            var second = harness.request();
            harness.reply(second, "{\"data\":[],\"nextCursor\":\"same\"}");
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Codex MCP inventory did not match issued grants");
        }
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var request = harness.request();
            harness.reply(request, "{\"data\":null,\"nextCursor\":null}");
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Codex MCP inventory did not match issued grants");
        }
    }

    @Test void unavailableInventoryFailsClosedWithinRequestDeadline() throws Exception {
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofMillis(20)));
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Codex MCP inventory did not match issued grants");
        }
    }

    @Test void failedExpectedServerCannotExposeAnUnexpectedTool() throws Exception {
        try (var harness = new Harness()) {
            var result = CompletableFuture.supplyAsync(() -> verifier.verify(harness.transport, "thread-a",
                    selected, Duration.ofSeconds(1)));
            var request = harness.request();
            harness.reply(request, "{\"data\":[{\"name\":\"" + alias
                    + "\",\"runtimeStatus\":\"failed\",\"tools\":{\"danger\":{\"name\":\"danger\"}}}],"
                    + "\"nextCursor\":null}");
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Codex MCP inventory did not match issued grants");
        }
    }

    private static String connected(String name, String tool) {
        return "{\"name\":\"" + name + "\",\"runtimeStatus\":\"connected\",\"tools\":{\""
                + tool + "\":{\"name\":\"" + tool + "\"}}}";
    }

    private final class Harness implements AutoCloseable {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport;
        Harness() {
            var properties = new CodexAppServerProperties();
            properties.setGracefulTerminateTimeout(Duration.ofMillis(20));
            properties.setForceKillTimeout(Duration.ofMillis(20));
            transport = new CodexJsonRpcTransport(json,
                    new StartedCodexAppServer(process, List.of("codex", "app-server", "--stdio"), Instant.now()),
                    properties);
        }
        JsonNode request() throws Exception { return json.readTree(process.readRequest()); }
        void reply(JsonNode request, String body) {
            process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":" + body + "}");
        }
        public void close() { transport.close(); }
    }
}
