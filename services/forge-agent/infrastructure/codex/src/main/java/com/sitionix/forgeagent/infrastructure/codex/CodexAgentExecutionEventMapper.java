package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventType;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

final class CodexAgentExecutionEventMapper {
    private static final int MAX_PAYLOAD_BYTES = 120_000;
    private static final Set<String> IGNORED_DELTA_METHODS = Set.of(
            "item/agentMessage/delta",
            "item/reasoning/summaryTextDelta",
            "item/reasoning/summaryPartAdded",
            "item/reasoning/textDelta",
            "item/commandExecution/outputDelta",
            "item/commandExecution/terminalInteraction",
            "item/fileChange/outputDelta",
            "item/fileChange/patchUpdated",
            "item/mcpToolCall/progress"
    );

    private final ObjectMapper objectMapper;
    private final CodexEventPayloadSanitizer sanitizer = new CodexEventPayloadSanitizer();

    CodexAgentExecutionEventMapper(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AgentExecutionEventCandidate turnStarted(final String turnId, final Instant observedAt) {
        return this.turnEvent(turnId, AgentExecutionEventStatus.STARTED, "started", observedAt);
    }

    AgentExecutionEventCandidate turnCompleted(final String turnId, final Instant observedAt) {
        return this.turnEvent(turnId, AgentExecutionEventStatus.COMPLETED, "completed", observedAt);
    }

    AgentExecutionEventCandidate turnFailed(final String turnId, final String message, final Instant observedAt) {
        final ObjectNode payload = this.objectMapper.createObjectNode();
        payload.put("providerTurnId", turnId);
        if (message != null && !message.isBlank()) this.putBounded(payload, "message", message);
        return this.event(AgentExecutionEventType.TURN, AgentExecutionEventStatus.FAILED, null,
                "turn:" + turnId + ":failed", payload, observedAt);
    }

    private AgentExecutionEventCandidate turnEvent(final String turnId,
                                                    final AgentExecutionEventStatus status,
                                                    final String stage,
                                                    final Instant observedAt) {
        final ObjectNode payload = this.objectMapper.createObjectNode();
        payload.put("providerTurnId", turnId);
        return this.event(AgentExecutionEventType.TURN, status, null,
                "turn:" + turnId + ":" + stage, payload, observedAt);
    }

    Optional<AgentExecutionEventCandidate> map(final String method, final JsonNode params,
                                                final Instant observedAt) {
        if (method == null || params == null || !params.isObject() || IGNORED_DELTA_METHODS.contains(method)) {
            return Optional.empty();
        }
        return switch (method) {
            case "turn/plan/updated" -> Optional.of(this.plan(params, observedAt));
            case "item/started" -> this.item(params, false, observedAt);
            case "item/completed" -> this.item(params, true, observedAt);
            case "thread/tokenUsage/updated" -> Optional.of(this.usage(params, observedAt));
            case "thread/compacted" -> Optional.of(this.event(
                    AgentExecutionEventType.CONTEXT_COMPACTION, AgentExecutionEventStatus.COMPLETED,
                    null, null, this.objectMapper.createObjectNode(), observedAt));
            case "warning", "configWarning", "deprecationNotice" -> this.diagnostic(
                    AgentExecutionEventType.WARNING, params, observedAt);
            case "error" -> this.diagnostic(AgentExecutionEventType.ERROR, params, observedAt);
            default -> Optional.empty();
        };
    }

    private AgentExecutionEventCandidate plan(final JsonNode params, final Instant observedAt) {
        final ObjectNode payload = this.objectMapper.createObjectNode();
        this.copyText(params, payload, "explanation", "explanation");
        final ArrayNode steps = payload.putArray("steps");
        final JsonNode rawPlan = params.path("plan");
        if (rawPlan.isArray()) {
            rawPlan.forEach(step -> {
                final ObjectNode normalized = this.objectMapper.createObjectNode();
                this.copyText(step, normalized, "step", "step");
                this.copyText(step, normalized, "status", "status");
                steps.add(normalized);
            });
        }
        return this.event(AgentExecutionEventType.PLAN, null, null, null, payload, observedAt);
    }

    private Optional<AgentExecutionEventCandidate> item(final JsonNode params, final boolean completed,
                                                         final Instant observedAt) {
        final JsonNode item = params.path("item");
        if (!item.isObject()) return Optional.empty();
        final String type = text(item.path("type"));
        final String itemId = text(item.path("id"));
        final String key = itemId == null ? null : "item:" + itemId + ":" + (completed ? "completed" : "started");
        return switch (type == null ? "" : type) {
            case "reasoning" -> completed ? this.reasoning(item, key, observedAt) : Optional.empty();
            case "commandExecution" -> Optional.of(this.command(item, completed, key, observedAt));
            case "fileChange" -> completed ? Optional.of(this.fileChange(item, key, observedAt)) : Optional.empty();
            case "mcpToolCall", "dynamicToolCall" -> Optional.of(this.tool(
                    item, type, completed, key, observedAt));
            case "agentMessage" -> completed ? this.agentMessage(item, key, observedAt) : Optional.empty();
            case "contextCompaction" -> Optional.of(this.event(
                    AgentExecutionEventType.CONTEXT_COMPACTION,
                    completed ? AgentExecutionEventStatus.COMPLETED : AgentExecutionEventStatus.STARTED,
                    null, key, this.statusPayload(item), observedAt));
            case "plan" -> completed ? Optional.of(this.planItem(item, key, observedAt)) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<AgentExecutionEventCandidate> reasoning(final JsonNode item, final String key,
                                                              final Instant observedAt) {
        final JsonNode summary = item.path("summary");
        if (!summary.isArray() || summary.isEmpty()) return Optional.empty();
        final ObjectNode payload = this.objectMapper.createObjectNode();
        payload.set("summary", this.sanitizer.sanitize(summary));
        return Optional.of(this.event(AgentExecutionEventType.REASONING_SUMMARY,
                AgentExecutionEventStatus.COMPLETED, null, key, payload, observedAt));
    }

    private AgentExecutionEventCandidate command(final JsonNode item, final boolean completed,
                                                   final String key, final Instant observedAt) {
        final ObjectNode payload = this.objectMapper.createObjectNode();
        this.copySanitizedText(item, payload, "command", "command");
        this.copySanitizedText(item, payload, "cwd", "cwd");
        this.copyNumber(item, payload, "exitCode", "exitCode");
        this.copyNumber(item, payload, "durationMs", "durationMs");
        final String output = firstText(item, "aggregatedOutput", "output");
        if (output != null) this.putBounded(payload, "output", output);
        final AgentExecutionEventStatus status = completed
                ? completedStatus(item) : AgentExecutionEventStatus.STARTED;
        return this.event(AgentExecutionEventType.COMMAND, status, null, key, payload, observedAt);
    }

    private AgentExecutionEventCandidate fileChange(final JsonNode item, final String key,
                                                      final Instant observedAt) {
        final ObjectNode payload = this.statusPayload(item);
        if (item.path("changes").isArray()) {
            final ArrayNode changes = payload.putArray("changes");
            item.path("changes").forEach(change -> {
                final ObjectNode normalized = this.objectMapper.createObjectNode();
                this.copySanitizedText(change, normalized, "path", "path");
                this.copyText(change, normalized, "kind", "operation");
                this.copyText(change, normalized, "type", "operation");
                this.copyText(change, normalized, "operation", "operation");
                this.copySanitizedText(change, normalized, "summary", "summary");
                changes.add(normalized);
            });
        }
        if (item.path("files").isArray()) {
            final ArrayNode paths = payload.putArray("paths");
            item.path("files").forEach(file -> {
                final String path = file.isTextual() ? text(file) : text(file.path("path"));
                if (path != null) paths.add(this.sanitizer.sanitizeText(path));
            });
        }
        this.copySanitizedText(item, payload, "summary", "summary");
        return this.event(AgentExecutionEventType.FILE_CHANGE, completedStatus(item), null, key, payload, observedAt);
    }

    private AgentExecutionEventCandidate tool(final JsonNode item, final String itemType, final boolean completed,
                                                final String key, final Instant observedAt) {
        final ObjectNode payload = this.statusPayload(item);
        payload.put("toolKind", "mcpToolCall".equals(itemType) ? "MCP" : "DYNAMIC");
        this.copyText(item, payload, "tool", "tool");
        this.copyText(item, payload, "name", "tool");
        this.copyText(item, payload, "server", "server");
        this.copyText(item, payload, "operation", "operation");
        final JsonNode result = item.path("result");
        if (!result.isMissingNode() && !result.isNull()) payload.set("responseSummary", this.sanitizer.sanitize(result));
        return this.event(AgentExecutionEventType.TOOL_CALL,
                completed ? completedStatus(item) : AgentExecutionEventStatus.STARTED,
                null, key, payload, observedAt);
    }

    private Optional<AgentExecutionEventCandidate> agentMessage(final JsonNode item, final String key,
                                                                 final Instant observedAt) {
        final String phase = text(item.path("phase"));
        if (phase != null && !"final_answer".equals(phase) && !"final".equals(phase)) return Optional.empty();
        final String message = firstText(item, "text", "message");
        if (message == null) return Optional.empty();
        final ObjectNode payload = this.objectMapper.createObjectNode();
        this.putBounded(payload, "message", message);
        return Optional.of(this.event(AgentExecutionEventType.AGENT_MESSAGE,
                AgentExecutionEventStatus.COMPLETED, "FINAL", key, payload, observedAt));
    }

    private AgentExecutionEventCandidate planItem(final JsonNode item, final String key,
                                                   final Instant observedAt) {
        final ObjectNode payload = this.objectMapper.createObjectNode();
        if (item.has("content")) payload.set("steps", this.sanitizer.sanitize(item.path("content")));
        this.copyText(item, payload, "explanation", "explanation");
        return this.event(AgentExecutionEventType.PLAN, AgentExecutionEventStatus.COMPLETED,
                null, key, payload, observedAt);
    }

    private AgentExecutionEventCandidate usage(final JsonNode params, final Instant observedAt) {
        final JsonNode usage = params.path("tokenUsage");
        final ObjectNode payload = this.objectMapper.createObjectNode();
        payload.set("total", this.normalizedUsage(usage.path("total")));
        payload.set("last", this.normalizedUsage(usage.path("last")));
        this.copyNumber(usage, payload, "modelContextWindow", "modelContextWindow");
        return this.event(AgentExecutionEventType.TOKEN_USAGE, null, null, null, payload, observedAt);
    }

    private ObjectNode normalizedUsage(final JsonNode source) {
        final ObjectNode usage = this.objectMapper.createObjectNode();
        this.copyFirstNumber(source, usage, "input", "inputTokens", "input");
        this.copyFirstNumber(source, usage, "cached", "cachedInputTokens", "cachedTokens", "cached");
        this.copyFirstNumber(source, usage, "cacheWrite", "cacheWriteTokens", "cacheWrite");
        this.copyFirstNumber(source, usage, "output", "outputTokens", "output");
        this.copyFirstNumber(source, usage, "reasoningOutput", "reasoningOutputTokens", "reasoningOutput");
        this.copyFirstNumber(source, usage, "total", "totalTokens", "total");
        return usage;
    }

    private Optional<AgentExecutionEventCandidate> diagnostic(final AgentExecutionEventType type,
                                                               final JsonNode params,
                                                               final Instant observedAt) {
        final String message = firstText(params.path("error"), "message");
        final String resolved = message == null ? firstText(params, "message", "summary") : message;
        if (resolved == null) return Optional.empty();
        final ObjectNode payload = this.objectMapper.createObjectNode();
        this.putBounded(payload, "message", resolved);
        return Optional.of(this.event(type, type == AgentExecutionEventType.ERROR
                ? AgentExecutionEventStatus.FAILED : null, null, null, payload, observedAt));
    }

    private AgentExecutionEventCandidate event(final AgentExecutionEventType type,
                                                final AgentExecutionEventStatus status,
                                                final String phase,
                                                final String key,
                                                final ObjectNode payload,
                                                final Instant observedAt) {
        try {
            String normalized = this.objectMapper.writeValueAsString(payload);
            if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                final CodexEventPayloadSanitizer.BoundedText bounded = this.sanitizer.boundedText(normalized);
                final ObjectNode fallback = this.objectMapper.createObjectNode();
                fallback.put("contentSummary", bounded.value());
                fallback.put("truncated", true);
                fallback.put("originalBytes", normalized.getBytes(StandardCharsets.UTF_8).length);
                fallback.put("storedBytes", bounded.storedBytes());
                normalized = this.objectMapper.writeValueAsString(fallback);
            }
            return new AgentExecutionEventCandidate(type, status, phase, key, normalized, observedAt);
        } catch (final JsonProcessingException failure) {
            throw new CodexTransportException("Codex event payload could not be normalized", failure);
        }
    }

    private ObjectNode statusPayload(final JsonNode item) {
        final ObjectNode payload = this.objectMapper.createObjectNode();
        this.copyText(item, payload, "status", "providerStatus");
        return payload;
    }

    private void putBounded(final ObjectNode target, final String field, final String value) {
        final CodexEventPayloadSanitizer.BoundedText bounded = this.sanitizer.boundedText(value);
        target.put(field, bounded.value());
        if (bounded.truncated()) {
            target.put("truncated", true);
            target.put("originalBytes", bounded.originalBytes());
            target.put("storedBytes", bounded.storedBytes());
        }
    }

    private void copySanitizedText(final JsonNode source, final ObjectNode target,
                                   final String sourceField, final String targetField) {
        final String value = text(source.path(sourceField));
        if (value != null) target.put(targetField, this.sanitizer.sanitizeText(value));
    }

    private void copyText(final JsonNode source, final ObjectNode target,
                          final String sourceField, final String targetField) {
        final String value = text(source.path(sourceField));
        if (value != null && !target.has(targetField)) target.put(targetField, value);
    }

    private void copyNumber(final JsonNode source, final ObjectNode target,
                            final String sourceField, final String targetField) {
        final JsonNode value = source.path(sourceField);
        if (value.isNumber()) target.set(targetField, value.deepCopy());
    }

    private void copyFirstNumber(final JsonNode source, final ObjectNode target,
                                 final String targetField, final String... sourceFields) {
        for (final String field : sourceFields) {
            final JsonNode value = source.path(field);
            if (value.isNumber()) {
                target.set(targetField, value.deepCopy());
                return;
            }
        }
    }

    private static AgentExecutionEventStatus completedStatus(final JsonNode item) {
        final String status = text(item.path("status"));
        if ("failed".equalsIgnoreCase(status) || item.path("exitCode").asInt(0) != 0) {
            return AgentExecutionEventStatus.FAILED;
        }
        if ("succeeded".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
            return AgentExecutionEventStatus.SUCCEEDED;
        }
        if (item.path("exitCode").isIntegralNumber() && item.path("exitCode").asInt() == 0) {
            return AgentExecutionEventStatus.SUCCEEDED;
        }
        return AgentExecutionEventStatus.COMPLETED;
    }

    private static String firstText(final JsonNode source, final String... fields) {
        for (final String field : fields) {
            final String value = text(source.path(field));
            if (value != null) return value;
        }
        return null;
    }

    private static String text(final JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
