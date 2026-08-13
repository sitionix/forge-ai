package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class CodexTurnStateTracker {

    private final Map<String, CodexExecutionState> executionsByThreadId = new HashMap<>();

    synchronized CodexExecutionState register(final String threadId) {
        final CodexExecutionState state = new CodexExecutionState(threadId);
        this.executionsByThreadId.put(threadId, state);
        return state;
    }

    synchronized void bindTurnId(final CodexExecutionState state, final String turnId) {
        if (this.executionsByThreadId.get(state.threadId()) != state || state.done()) {
            return;
        }
        state.bindTurnId(turnId);
    }

    synchronized void remove(final CodexExecutionState state) {
        this.executionsByThreadId.remove(state.threadId(), state);
    }

    synchronized void failAll(final RuntimeException exception) {
        this.executionsByThreadId.values().forEach(active -> active.fail(exception));
        this.executionsByThreadId.clear();
    }

    synchronized int activeTurnCount() {
        return (int) this.executionsByThreadId.values().stream()
                .filter(execution -> execution.hasTurnId() && !execution.done())
                .count();
    }

    synchronized void handleNotification(final String method, final JsonNode params) {
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

    synchronized JsonNode handleServerRequest(final String method, final JsonNode params) {
        if (CodexProtocol.COMMAND_APPROVAL.equals(method) || CodexProtocol.FILE_CHANGE_APPROVAL.equals(method)) {
            this.failRequestOwner(params);
            return this.decline();
        }
        this.failRequestOwner(params);
        throw new UnsupportedOperationException("Unsupported Codex server request method=" + method);
    }

    String requireThreadId(final JsonNode payload) {
        this.requireObject(payload);
        final String threadId = this.value(payload.path("thread"), "id");
        if (threadId == null) {
            throw this.executionFailed();
        }
        return threadId;
    }

    String requireTurnId(final JsonNode payload) {
        this.requireObject(payload);
        final String turnId = this.value(payload.path("turn"), "id");
        if (turnId == null) {
            throw this.executionFailed();
        }
        return turnId;
    }

    private void handleGenerationItem(final JsonNode params, final String method, final boolean captureAgentMessage) {
        this.requireObject(params);
        final String threadId = this.notificationThreadId(params);
        final String turnId = this.notificationTurnId(params);
        if (threadId == null || turnId == null) {
            throw this.executionFailed();
        }
        final CodexExecutionState execution = this.execution(threadId);
        if (execution == null || execution.done()) {
            return;
        }
        execution.bindTurnId(turnId);

        final JsonNode item = params.path("item");
        this.requireObject(item);
        final String itemType = this.nonBlank(item.path("type"));
        final RuntimeException violation = CodexGenerationPolicy.violationFor(itemType);
        if (violation != null) {
            execution.failPolicyViolation(violation);
            return;
        }
        if (captureAgentMessage && CodexGenerationPolicy.capturesFinalOutput(itemType)) {
            this.extractText(item).ifPresent(text -> execution.addAgentMessage(text, this.resolvePhase(item)));
        }
    }

    private void handleTurnCompleted(final JsonNode params) {
        this.requireObject(params);
        final String threadId = this.notificationThreadId(params);
        final String turnId = this.value(params.path("turn"), "id");
        if (threadId == null || turnId == null) {
            throw this.executionFailed();
        }
        final CodexExecutionState execution = this.execution(threadId);
        if (execution == null || execution.done()) {
            return;
        }
        execution.bindTurnId(turnId);
        this.complete(execution, this.resolveStatus(params), this.providerFailure(params));
    }

    private void complete(final CodexExecutionState execution, final String status, final String providerFailure) {
        if ("completed".equals(status)) {
            final Optional<String> output = execution.finalAgentMessage();
            if (output.isEmpty() || output.get().isBlank()) {
                execution.fail(this.executionFailed());
                return;
            }
            execution.complete(output.get());
            return;
        }
        execution.fail(this.executionFailed(providerFailure));
    }

    private void failRequestOwner(final JsonNode params) {
        final String threadId = this.notificationThreadId(params);
        if (threadId != null) {
            final CodexExecutionState execution = this.execution(threadId);
            if (execution != null && !execution.done()) {
                final String turnId = this.notificationTurnId(params);
                if (turnId != null) {
                    execution.bindTurnId(turnId);
                }
                execution.failPolicyViolation(this.executionFailed());
            }
            return;
        }
        this.executionsByThreadId.values().forEach(execution -> execution.failPolicyViolation(this.executionFailed()));
    }

    private CodexExecutionState execution(final String threadId) {
        return this.executionsByThreadId.get(threadId);
    }

    private String notificationThreadId(final JsonNode params) {
        return this.value(params, "threadId");
    }

    private String notificationTurnId(final JsonNode params) {
        return this.value(params, "turnId");
    }

    private String resolveStatus(final JsonNode params) {
        final String status = this.value(params.path("turn"), "status");
        if (status == null) {
            throw this.executionFailed();
        }
        return status;
    }

    private String providerFailure(final JsonNode params) {
        return this.nonBlank(params.path("turn").path("error").path("message"));
    }

    private String resolvePhase(final JsonNode item) {
        if (!item.has("phase") || item.path("phase").isNull()) {
            return null;
        }
        return this.nonBlank(item.path("phase"));
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
        return Optional.empty();
    }

    private void requireObject(final JsonNode node) {
        if (node == null || !node.isObject()) {
            throw this.executionFailed();
        }
    }

    private ObjectNode decline() {
        final ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("decision", "decline");
        return response;
    }

    private CodexTransportException executionFailed() {
        return this.executionFailed(null);
    }

    private CodexTransportException executionFailed(final String message) {
        return new CodexTransportException(message == null || message.isBlank() ? "Codex execution failed." : message);
    }
}
