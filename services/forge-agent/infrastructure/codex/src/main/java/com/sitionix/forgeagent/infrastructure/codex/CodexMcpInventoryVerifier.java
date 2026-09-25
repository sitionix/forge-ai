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

/** Audits Codex's thread-scoped MCP inventory before allowing any turn to start. */
final class CodexMcpInventoryVerifier {
    private static final String MISMATCH = "Codex MCP inventory did not match issued grants";
    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 20;
    private final ObjectMapper json;

    CodexMcpInventoryVerifier(ObjectMapper json) { this.json = json; }

    Result verify(CodexJsonRpcTransport transport, String threadId, McpExecutionSelection selection,
                  Duration timeout) {
        if (threadId == null || threadId.isBlank() || selection == null || timeout == null
                || timeout.isZero() || timeout.isNegative()) throw mismatch();
        Map<String, Set<String>> approved = new HashMap<>();
        for (var entry : selection.entries()) {
            Set<String> names = new HashSet<>();
            entry.tools().forEach(tool -> names.add(tool.name()));
            if (approved.putIfAbsent(entry.alias(), names) != null) throw mismatch();
        }
        Map<String, Set<String>> effective = new HashMap<>();
        List<McpExecutionSelection.Diagnostic> diagnostics = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        long deadline = System.nanoTime() + timeout.toNanos();
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
                Set<String> allowed = approved.get(alias);
                if (allowed == null || !server.path("runtimeStatus").isTextual()) throw mismatch();
                JsonNode tools = server.path("tools");
                Set<String> names = new HashSet<>();
                if (tools.isObject()) {
                    var fields = tools.fields();
                    while (fields.hasNext()) {
                        var tool = fields.next();
                        if (!allowed.contains(tool.getKey()) || !tool.getValue().isObject()
                                || !tool.getKey().equals(tool.getValue().path("name").asText())) throw mismatch();
                        names.add(tool.getKey());
                    }
                }
                if (!"connected".equals(server.path("runtimeStatus").asText())) {
                    diagnostics.add(new McpExecutionSelection.Diagnostic(
                            selection.entries().stream().filter(entry -> entry.alias().equals(alias))
                                    .findFirst().orElseThrow(CodexMcpInventoryVerifier::mismatch).connectionId(),
                            "MCP_CONNECTION_UNAVAILABLE"));
                    continue;
                }
                if (!tools.isObject() || names.size() != allowed.size()) throw mismatch();
                effective.put(alias, Set.copyOf(names));
            }
            JsonNode next = page.path("nextCursor");
            if (next.isNull()) break;
            if (!next.isTextual() || next.asText().isBlank() || !cursors.add(next.asText())) throw mismatch();
            cursor = next.asText();
            if (pageNumber == MAX_PAGES - 1) throw mismatch();
        }
        for (var entry : selection.entries()) {
            if (!seen.contains(entry.alias())) {
                diagnostics.add(new McpExecutionSelection.Diagnostic(entry.connectionId(), "MCP_TOOL_UNAVAILABLE"));
            }
        }
        return new Result(Map.copyOf(effective), List.copyOf(diagnostics));
    }

    private static CodexTransportException mismatch() { return new CodexTransportException(MISMATCH); }

    record Result(Map<String, Set<String>> effectiveTools, List<McpExecutionSelection.Diagnostic> diagnostics) { }
}
