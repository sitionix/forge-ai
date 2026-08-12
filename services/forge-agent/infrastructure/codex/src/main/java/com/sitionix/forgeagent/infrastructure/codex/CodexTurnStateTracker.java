package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class CodexTurnStateTracker {

    private static final int MAX_BUFFERED_PRE_REGISTRATION_NOTIFICATIONS = 32;

    private final Map<CodexTurnKey, CodexExecutionState> activeTurns = new HashMap<>();
    private final Map<String, PreRegistrationTurn> preRegistrationTurns = new HashMap<>();

    synchronized void beginPreRegistration(final String threadId) {
        this.preRegistrationTurns.put(threadId, new PreRegistrationTurn());
    }

    synchronized void endPreRegistration(final String threadId) {
        this.preRegistrationTurns.remove(threadId);
    }

    synchronized CodexExecutionState register(final String threadId, final String turnId) {
        final CodexTurnKey key = new CodexTurnKey(threadId, turnId);
        final CodexExecutionState execution = new CodexExecutionState(key);
        this.activeTurns.put(key, execution);

        try {
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
        } catch (final RuntimeException exception) {
            this.activeTurns.remove(key, execution);
            execution.fail(exception);
            throw exception;
        }
    }

    synchronized void remove(final CodexExecutionState execution) {
        this.activeTurns.remove(execution.key(), execution);
    }

    synchronized void failAll(final RuntimeException exception) {
        this.activeTurns.values().forEach(active -> active.fail(exception));
        this.activeTurns.clear();
        this.preRegistrationTurns.clear();
    }

    synchronized int activeTurnCount() {
        return this.activeTurns.size();
    }

    synchronized int bufferedNotificationCount() {
        return this.preRegistrationTurns.values().stream()
                .mapToInt(PreRegistrationTurn::bufferedNotificationCount)
                .sum();
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
            this.failServerRequestOwner(params);
            return this.decline();
        }
        this.failServerRequestOwner(params);
        throw new UnsupportedOperationException("Unsupported Codex server request method=" + method);
    }

    String requireThreadId(final JsonNode payload) {
        this.requireObject(payload, "thread/start result");
        final String threadId = this.value(payload.path("thread"), "id");
        if (threadId == null) {
            throw this.executionFailed();
        }
        return threadId;
    }

    String requireTurnId(final JsonNode payload) {
        this.requireObject(payload, "turn/start result");
        final String turnId = this.value(payload.path("turn"), "id");
        if (turnId == null) {
            throw this.executionFailed();
        }
        return turnId;
    }

    private void handleGenerationItem(final JsonNode params, final String method, final boolean captureAgentMessage) {
        this.requireObject(params, method + " params");
        final String threadId = this.notificationThreadId(params);
        final String turnId = this.notificationTurnId(params);
        if (threadId == null || turnId == null) {
            throw this.executionFailed();
        }
        final JsonNode item = params.path("item");
        this.requireObject(item, method + " item");
        final String itemType = this.nonBlank(item.path("type"));
        final AgentExecutionException violation = CodexGenerationPolicy.violationFor(itemType);
        final CodexExecutionState execution = this.findActive(threadId, turnId);
        if (execution == null) {
            this.bufferPreRegistration(threadId, params, method, turnId, violation);
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
            this.extractText(item).ifPresent(text -> execution.addAgentMessage(text, this.resolvePhase(item)));
        }
    }

    private void handleTurnCompleted(final JsonNode params) {
        this.requireObject(params, CodexProtocol.TURN_COMPLETED + " params");
        final String threadId = this.notificationThreadId(params);
        final String turnId = this.value(params.path("turn"), "id");
        final String status = this.resolveStatus(params);
        if (threadId == null || turnId == null) {
            throw this.executionFailed();
        }
        final CodexExecutionState execution = this.findActive(threadId, turnId);
        if (execution == null) {
            this.bufferPreRegistration(threadId, params, CodexProtocol.TURN_COMPLETED, turnId, null);
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
        final String threadId = this.notificationThreadId(params);
        final String turnId = this.notificationTurnId(params);
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

    private CodexExecutionState findActive(final String threadId, final String turnId) {
        return this.activeTurns.get(new CodexTurnKey(threadId, turnId));
    }

    private void bufferPreRegistration(final String threadId,
                                       final JsonNode params,
                                       final String method,
                                       final String turnId,
                                       final AgentExecutionException violation) {
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

        void buffer(final BufferedNotification notification) {
            if (this.failure != null) {
                return;
            }
            if (this.bufferedNotifications.size() >= MAX_BUFFERED_PRE_REGISTRATION_NOTIFICATIONS) {
                this.fail(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
                return;
            }
            this.bufferedNotifications.add(notification);
        }

        List<BufferedNotification> notificationsFor(final String turnId) {
            return this.bufferedNotifications.stream()
                    .filter(notification -> turnId.equals(notification.turnId()))
                    .toList();
        }

        void fail(final RuntimeException exception) {
            this.failure = exception;
            this.bufferedNotifications.clear();
        }

        RuntimeException failure() {
            return this.failure;
        }

        int bufferedNotificationCount() {
            return this.bufferedNotifications.size();
        }
    }

    private record BufferedNotification(String turnId, String method, JsonNode params) {
    }
}
