package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.domain.model.McpExecutionSelection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Audits Codex's thread-scoped MCP inventory before allowing any turn to start. */
final class CodexMcpInventoryVerifier {
    private static final String MISMATCH = "Codex MCP inventory did not match issued grants";
    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 20;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private final ObjectMapper json;

    CodexMcpInventoryVerifier(ObjectMapper json) { this.json = json; }

    Result verify(CodexJsonRpcTransport transport, String threadId, McpExecutionSelection selection,
                  Duration timeout) {
        if (threadId == null || threadId.isBlank() || selection == null || timeout == null
                || timeout.isZero() || timeout.isNegative()) throw mismatch();
        Map<String, ExpectedServer> expected = new HashMap<>();
        for (var entry : selection.entries()) {
            Set<String> names = new HashSet<>();
            entry.tools().forEach(tool -> names.add(tool.name()));
            if (expected.putIfAbsent(entry.alias(), new ExpectedServer(entry.connectionId(), Set.copyOf(names))) != null)
                throw mismatch();
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Snapshot snapshot = snapshot(transport, threadId, expected, deadline);
            if (snapshot.pendingConnections().isEmpty() && snapshot.missingServers().isEmpty()) {
                return new Result(snapshot.effectiveTools(), snapshot.diagnostics());
            }
            if (!awaitNextPoll(deadline)) {
                var diagnostics = new ArrayList<>(snapshot.diagnostics());
                snapshot.pendingConnections().forEach(alias -> diagnostics.add(new McpExecutionSelection.Diagnostic(
                        expected.get(alias).connectionId(),
                        McpExecutionSelection.DiagnosticCode.MCP_CONNECTION_UNAVAILABLE)));
                snapshot.missingServers().forEach(alias -> diagnostics.add(new McpExecutionSelection.Diagnostic(
                        expected.get(alias).connectionId(),
                        McpExecutionSelection.DiagnosticCode.MCP_TOOL_UNAVAILABLE)));
                return new Result(snapshot.effectiveTools(), List.copyOf(diagnostics));
            }
        }
    }

    private Snapshot snapshot(CodexJsonRpcTransport transport, String threadId,
                              Map<String, ExpectedServer> expected, long deadline) {
        Map<String, Set<String>> effective = new HashMap<>();
        List<McpExecutionSelection.Diagnostic> diagnostics = new ArrayList<>();
        Set<String> pendingConnections = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;

        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw mismatch();
            ObjectNode params = json.createObjectNode().put("threadId", threadId).put("limit", PAGE_LIMIT);
            if (cursor != null) params.put("cursor", cursor);
            final JsonNode page;
            try {
                page = transport.request(CodexProtocol.MCP_SERVER_STATUS_LIST, params, Duration.ofNanos(remaining));
            } catch (RuntimeException ignored) {
                throw mismatch();
            }
            if (page == null || !page.isObject() || !page.path("data").isArray()) throw mismatch();

            for (JsonNode server : page.path("data")) {
                if (!server.isObject() || !server.path("name").isTextual()
                        || !seen.add(server.path("name").asText()) || seen.size() > MAX_PAGES * PAGE_LIMIT)
                    throw mismatch();
                String alias = server.path("name").asText();
                ExpectedServer expectedServer = expected.get(alias);
                if (expectedServer == null || !server.path("runtimeStatus").isTextual()) throw mismatch();

                JsonNode tools = server.path("tools");
                Set<String> names = new HashSet<>();
                if (tools.isObject()) {
                    var fields = tools.fields();
                    while (fields.hasNext()) {
                        var tool = fields.next();
                        if (!expectedServer.tools().contains(tool.getKey()) || !tool.getValue().isObject()
                                || !tool.getKey().equals(tool.getValue().path("name").asText())) throw mismatch();
                        names.add(tool.getKey());
                    }
                }

                switch (RuntimeStatus.fromWire(server.path("runtimeStatus").asText())) {
                    case CONNECTED -> {
                        if (!tools.isObject() || names.size() != expectedServer.tools().size()) throw mismatch();
                        effective.put(alias, Set.copyOf(names));
                    }
                    case NOT_STARTED, STARTING -> pendingConnections.add(alias);
                    case AUTHENTICATION_REQUIRED, FAILED, CANCELLED, DISABLED ->
                            diagnostics.add(new McpExecutionSelection.Diagnostic(
                                    expectedServer.connectionId(),
                                    McpExecutionSelection.DiagnosticCode.MCP_CONNECTION_UNAVAILABLE));
                }
            }

            JsonNode next = page.path("nextCursor");
            if (next.isNull()) break;
            if (!next.isTextual() || next.asText().isBlank() || !cursors.add(next.asText())) throw mismatch();
            cursor = next.asText();
            if (pageNumber == MAX_PAGES - 1) throw mismatch();
        }

        Set<String> missing = new HashSet<>(expected.keySet());
        missing.removeAll(seen);
        return new Snapshot(Map.copyOf(effective), List.copyOf(diagnostics),
                Set.copyOf(pendingConnections), Set.copyOf(missing));
    }

    private static boolean awaitNextPoll(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, POLL_INTERVAL.toNanos()));
            return deadline - System.nanoTime() > 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw mismatch();
        }
    }

    private enum RuntimeStatus {
        NOT_STARTED("notStarted"),
        STARTING("starting"),
        CONNECTED("connected"),
        AUTHENTICATION_REQUIRED("authenticationRequired"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        DISABLED("disabled");

        private final String wireValue;

        RuntimeStatus(String wireValue) { this.wireValue = wireValue; }

        static RuntimeStatus fromWire(String wireValue) {
            for (var status : values()) {
                if (status.wireValue.equals(wireValue)) return status;
            }
            throw mismatch();
        }
    }

    private record ExpectedServer(java.util.UUID connectionId, Set<String> tools) { }

    private record Snapshot(Map<String, Set<String>> effectiveTools,
                            List<McpExecutionSelection.Diagnostic> diagnostics,
                            Set<String> pendingConnections,
                            Set<String> missingServers) { }

    private static CodexTransportException mismatch() { return new CodexTransportException(MISMATCH); }

    record Result(Map<String, Set<String>> effectiveTools, List<McpExecutionSelection.Diagnostic> diagnostics) { }
}
