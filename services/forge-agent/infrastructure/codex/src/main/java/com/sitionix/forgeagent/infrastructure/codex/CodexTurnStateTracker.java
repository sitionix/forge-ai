package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class CodexTurnStateTracker {

    private static final int MAX_BUFFERED_PRE_REGISTRATION_NOTIFICATIONS = 32;

    private final Map<CodexTurnKey, CodexExecutionState> activeTurns = new ConcurrentHashMap<>();
    private final Map<String, PreRegistrationTurn> preRegistrationTurns = new ConcurrentHashMap<>();

    void beginPreRegistration(final String threadId) {
        this.preRegistrationTurns.put(threadId, new PreRegistrationTurn());
    }

    void endPreRegistration(final String threadId) {
        this.preRegistrationTurns.remove(threadId);
    }

    CodexExecutionState register(final String threadId, final String turnId) {
        final CodexTurnKey key = new CodexTurnKey(threadId, turnId);
        final CodexExecutionState execution = new CodexExecutionState(key);
        this.activeTurns.put(key, execution);

        final PreRegistrationTurn preRegistration = this.preRegistrationTurns.remove(threadId);
        if (preRegistration == null) {
            return execution;
        }
        if (preRegistration.failure() != null) {
            execution.failPolicyViolation(preRegistration.failure());
            return execution;
        }
        preRegistration.notificationsFor(turnId).forEach(notification ->
                this.handleNotification(notification.method(), notification.params()));
        return execution;
    }

    void remove(final CodexExecutionState execution) {
        this.activeTurns.remove(execution.key(), execution);
    }

    void failAll(final RuntimeException exception) {
        this.activeTurns.values().forEach(active -> active.fail(exception));
        this.activeTurns.clear();
        this.preRegistrationTurns.clear();
    }

    int activeTurnCount() {
        return this.activeTurns.size();
    }

    int bufferedNotificationCount() {
        return this.preRegistrationTurns.values().stream()
                .mapToInt(PreRegistrationTurn::bufferedNotificationCount)
                .sum();
    }

    void handleNotification(final String method, final JsonNode params) {
        if (CodexProtocol.ITEM_STARTED.equals(method)) {
            this.handleGenerationItem(params, method, false);
            return;
        }
        if (CodexProtocol.ITEM_COMPLETED.equals(method)) {
            this.handleGenerationItem(params, method, true);
            return;
        }
        if (CodexProtocol.TURN_COMPLETED.equals(method)) {
            this.handleTurnCompleted(params);
        }
    }

    JsonNode handleServerRequest(final String method, final JsonNode params) {
        if (CodexProtocol.COMMAND_APPROVAL.equals(method) || CodexProtocol.FILE_CHANGE_APPROVAL.equals(method)) {
            this.failServerRequestOwner(params);
            return this.decline();
        }
        this.failServerRequestOwner(params);
        throw new UnsupportedOperationException("Unsupported Codex server request method=" + method);
    }

    String requireThreadId(final JsonNode payload) {
        this.requireObject(payload, "thread/start result");
        return this.resolveThreadId(payload, true);
    }

    String requireTurnId(final JsonNode payload) {
        this.requireObject(payload, "turn/start result");
        return this.resolveTurnId(payload, "turn/start", true);
    }

    private void handleGenerationItem(final JsonNode params, final String method, final boolean captureAgentMessage) {
        this.requireObject(params, method + " params");
        final String turnId = this.resolveTurnId(params, method, false);
        if (turnId == null) {
            throw this.executionFailed();
        }
        final JsonNode item = params.has("item") ? params.path("item") : params;
        this.requireObject(item, method + " item");
        final String itemType = this.nonBlank(item.path("type"));
        final AgentExecutionException violation = CodexGenerationPolicy.violationFor(itemType);
        final CodexExecutionState execution = this.findActive(params, turnId);
        if (execution == null) {
            this.bufferPreRegistration(params, method, turnId, violation);
            return;
        }
        if (execution.done()) {
            return;
        }
        if (violation != null) {
            execution.failPolicyViolation(violation);
            return;
        }
        if (captureAgentMessage && CodexGenerationPolicy.capturesFinalOutput(itemType)) {
            this.extractText(item).ifPresent(execution::addAgentMessage);
        }
    }

    private void handleTurnCompleted(final JsonNode params) {
        this.requireObject(params, CodexProtocol.TURN_COMPLETED + " params");
        final String turnId = this.resolveTurnId(params, CodexProtocol.TURN_COMPLETED, true);
        final String status = this.resolveStatus(params);
        final CodexExecutionState execution = this.findActive(params, turnId);
        if (execution == null) {
            this.bufferPreRegistration(params, CodexProtocol.TURN_COMPLETED, turnId, null);
            return;
        }
        if (execution.done()) {
            return;
        }
        this.complete(execution, status);
    }

    private void complete(final CodexExecutionState execution, final String status) {
        if ("completed".equals(status)) {
            final Optional<String> output = execution.finalAgentMessage();
            if (output.isEmpty() || output.get().isBlank()) {
                execution.fail(this.executionFailed());
                return;
            }
            execution.complete(output.get());
            return;
        }
        if ("failed".equals(status) || "interrupted".equals(status)) {
            execution.fail(this.executionFailed());
            return;
        }
        execution.fail(this.executionFailed());
    }

    private void failServerRequestOwner(final JsonNode params) {
        final String threadId = this.resolveThreadId(params, false);
        final String turnId = this.resolveTurnId(params, "server request", false);
        final AgentExecutionException failure = this.executionFailed();

        if (threadId != null && turnId != null) {
            final CodexExecutionState execution = this.activeTurns.get(new CodexTurnKey(threadId, turnId));
            if (execution != null && !execution.done()) {
                execution.failPolicyViolation(failure);
                return;
            }
            final PreRegistrationTurn preRegistration = this.preRegistrationTurns.get(threadId);
            if (preRegistration != null) {
                preRegistration.fail(failure);
            }
            return;
        }

        if (threadId != null) {
            final PreRegistrationTurn preRegistration = this.preRegistrationTurns.get(threadId);
            if (preRegistration != null) {
                preRegistration.fail(failure);
                return;
            }
        }

        this.preRegistrationTurns.values().forEach(preRegistration -> preRegistration.fail(failure));
        this.activeTurns.values().forEach(execution -> execution.failPolicyViolation(failure));
    }

    private CodexExecutionState findActive(final JsonNode params, final String turnId) {
        final String threadId = this.resolveThreadId(params, false);
        if (threadId == null) {
            return null;
        }
        return this.activeTurns.get(new CodexTurnKey(threadId, turnId));
    }

    private void bufferPreRegistration(final JsonNode params,
                                       final String method,
                                       final String turnId,
                                       final AgentExecutionException violation) {
        final String threadId = this.resolveThreadId(params, false);
        if (threadId == null) {
            return;
        }
        final PreRegistrationTurn preRegistration = this.preRegistrationTurns.get(threadId);
        if (preRegistration == null) {
            return;
        }
        if (violation != null) {
            preRegistration.fail(violation);
            return;
        }
        preRegistration.buffer(new BufferedNotification(turnId, method, params.deepCopy()));
    }

    private String resolveThreadId(final JsonNode params, final boolean required) {
        return this.resolveIdentity(
                required ? "Codex result omitted threadId" : null,
                this.value(params, "threadId"),
                this.value(params == null ? null : params.path("thread"), "id"),
                this.value(params == null ? null : params.path("thread"), "sessionId")
        );
    }

    private String resolveTurnId(final JsonNode params, final String context, final boolean required) {
        final JsonNode item = params == null ? null : params.path("item");
        return this.resolveIdentity(
                required ? "Codex " + context + " omitted turnId" : null,
                this.value(params, "turnId"),
                this.value(params == null ? null : params.path("turn"), "id"),
                this.value(item, "turnId"),
                this.value(item == null ? null : item.path("turn"), "id")
        );
    }

    private String resolveStatus(final JsonNode params) {
        return this.resolveIdentity(
                "Codex turn/completed omitted status",
                this.value(params, "status"),
                this.value(params.path("turn"), "status")
        );
    }

    private String resolveIdentity(final String requiredMessage, final String... values) {
        String resolved = null;
        for (final String value : values) {
            if (value == null) {
                continue;
            }
            if (resolved == null) {
                resolved = value;
            } else if (!resolved.equals(value)) {
                throw this.executionFailed();
            }
        }
        if (resolved == null && requiredMessage != null) {
            throw this.executionFailed();
        }
        return resolved;
    }

    private String value(final JsonNode node, final String field) {
        if (node == null || !node.isObject() || !node.has(field)) {
            return null;
        }
        return this.nonBlank(node.path(field));
    }

    private String nonBlank(final JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        final String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private Optional<String> extractText(final JsonNode payload) {
        if (payload.has("text") && payload.path("text").isTextual()) {
            return Optional.of(payload.path("text").asText());
        }
        if (payload.has("content")) {
            final JsonNode content = payload.path("content");
            if (content.isTextual()) {
                return Optional.of(content.asText());
            }
            if (content.isArray()) {
                final StringBuilder text = new StringBuilder();
                content.forEach(part -> {
                    if (part.isObject() && part.path("text").isTextual()) {
                        text.append(part.path("text").asText());
                    }
                });
                if (!text.isEmpty()) {
                    return Optional.of(text.toString());
                }
            }
        }
        if (payload.has("message")) {
            final JsonNode message = payload.path("message");
            if (message.isTextual()) {
                return Optional.of(message.asText());
            }
            if (message.isObject()) {
                return this.extractText(message);
            }
        }
        return Optional.empty();
    }

    private void requireObject(final JsonNode node, final String context) {
        if (node == null || !node.isObject()) {
            throw this.executionFailed();
        }
    }

    private ObjectNode decline() {
        final ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("decision", "decline");
        return response;
    }

    private AgentExecutionException executionFailed() {
        return new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.");
    }

    private static final class PreRegistrationTurn {

        private final List<BufferedNotification> bufferedNotifications = new ArrayList<>();
        private RuntimeException failure;

        synchronized void buffer(final BufferedNotification notification) {
            if (this.failure != null) {
                return;
            }
            if (this.bufferedNotifications.size() >= MAX_BUFFERED_PRE_REGISTRATION_NOTIFICATIONS) {
                this.fail(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
                return;
            }
            this.bufferedNotifications.add(notification);
        }

        synchronized List<BufferedNotification> notificationsFor(final String turnId) {
            return this.bufferedNotifications.stream()
                    .filter(notification -> turnId.equals(notification.turnId()))
                    .toList();
        }

        synchronized void fail(final RuntimeException exception) {
            this.failure = exception;
            this.bufferedNotifications.clear();
        }

        synchronized RuntimeException failure() {
            return this.failure;
        }

        synchronized int bufferedNotificationCount() {
            return this.bufferedNotifications.size();
        }
    }

    private record BufferedNotification(String turnId, String method, JsonNode params) {
    }
}
