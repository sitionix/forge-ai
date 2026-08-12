package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class CodexTurnStateTracker {

    private static final String ITEM_COMPLETED = "item/completed";
    private static final String ITEM_STARTED = "item/started";
    private static final String TURN_COMPLETED = "turn/completed";
    private static final String COMMAND_APPROVAL = "item/commandExecution/requestApproval";
    private static final String FILE_CHANGE_APPROVAL = "item/fileChange/requestApproval";
    private static final String FAIL_CLOSED_SERVER_REQUEST = "server-request/unsupported-side-effect";
    private static final int MAX_BUFFERED_PRE_REGISTRATION_NOTIFICATIONS = 32;
    private static final Map<String, Boolean> SAFE_ITEM_TYPES = Map.of(
            "userMessage", true,
            "reasoning", true,
            "agentMessage", true,
            "plan", true,
            "contextCompaction", true
    );
    private static final Map<String, Boolean> FORBIDDEN_ITEM_TYPES = Map.of(
            "commandExecution", true,
            "fileChange", true,
            "mcpToolCall", true,
            "dynamicToolCall", true,
            "webSearch", true,
            "collabToolCall", true,
            "imageView", true
    );

    private final Map<CodexTurnKey, ActiveTurn> activeByKey = new ConcurrentHashMap<>();
    private final Map<String, ActiveTurn> activeByTurnId = new ConcurrentHashMap<>();
    private final Map<String, List<BufferedNotification>> bufferedByTurnId = new ConcurrentHashMap<>();
    private final Map<String, Boolean> preRegistrationThreadIds = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> failedPreRegistrationThreadIds = new ConcurrentHashMap<>();

    void beginPreRegistration(final String threadId) {
        this.preRegistrationThreadIds.put(threadId, true);
    }

    void endPreRegistration(final String threadId) {
        this.preRegistrationThreadIds.remove(threadId);
        this.bufferedByTurnId.entrySet().removeIf(entry -> entry.getValue().stream()
                .map(BufferedNotification::params)
                .map(params -> this.resolveThreadId(params, false))
                .anyMatch(threadId::equals));
        this.failedPreRegistrationThreadIds.remove(threadId);
    }

    ActiveTurn register(final String threadId, final String turnId) {
        final ActiveTurn active = new ActiveTurn(new CodexTurnKey(threadId, turnId));
        this.activeByKey.put(active.key(), active);
        this.activeByTurnId.put(turnId, active);
        this.preRegistrationThreadIds.remove(threadId);
        this.replayBuffered(active);
        final RuntimeException preRegistrationFailure = this.failedPreRegistrationThreadIds.remove(threadId);
        if (preRegistrationFailure != null && !active.done()) {
            active.failPolicyViolation(preRegistrationFailure);
        }
        return active;
    }

    void remove(final ActiveTurn active) {
        this.activeByKey.remove(active.key(), active);
        this.activeByTurnId.remove(active.key().turnId(), active);
        this.bufferedByTurnId.remove(active.key().turnId());
    }

    void failAll(final RuntimeException exception) {
        this.activeByKey.values().forEach(active -> active.fail(exception));
        this.activeByKey.clear();
        this.activeByTurnId.clear();
        this.bufferedByTurnId.clear();
        this.preRegistrationThreadIds.clear();
        this.failedPreRegistrationThreadIds.clear();
    }

    int activeTurnCount() {
        return this.activeByKey.size();
    }

    int bufferedNotificationCount() {
        return this.bufferedByTurnId.values().stream().mapToInt(List::size).sum();
    }

    void handleNotification(final String method, final JsonNode params) {
        if (ITEM_STARTED.equals(method)) {
            this.handleItemStarted(params);
            return;
        }
        if (ITEM_COMPLETED.equals(method)) {
            this.handleItemCompleted(params);
            return;
        }
        if (TURN_COMPLETED.equals(method)) {
            this.handleTurnCompleted(params);
        }
    }

    JsonNode handleServerRequest(final String method, final JsonNode params) {
        if (COMMAND_APPROVAL.equals(method) || FILE_CHANGE_APPROVAL.equals(method)) {
            this.failRequestTurn(params);
            return this.decline();
        }
        this.failRequestTurn(params);
        throw new UnsupportedOperationException("Unsupported Codex server request method=" + method);
    }

    private void handleItemStarted(final JsonNode params) {
        this.handleGenerationItem(params, ITEM_STARTED, false);
    }

    private void handleItemCompleted(final JsonNode params) {
        this.handleGenerationItem(params, ITEM_COMPLETED, true);
    }

    private void handleGenerationItem(final JsonNode params, final String method, final boolean captureAgentMessage) {
        this.requireObject(params, method + " params");
        final String turnId = this.resolveTurnId(params, method, false);
        if (turnId == null) {
            throw this.protocolFailure("Codex " + method + " omitted turnId");
        }
        final JsonNode item = params.has("item") ? params.path("item") : params;
        this.requireObject(item, method + " item");
        final String itemType = this.nonBlank(item.path("type"));
        final AgentExecutionException violation = this.itemTypeViolation(itemType);
        final ActiveTurn active = this.findActive(params, turnId);
        if (active == null) {
            this.bufferPreRegistrationOnly(turnId, method, params);
            return;
        }
        if (active.done()) {
            return;
        }
        if (violation != null) {
            active.failPolicyViolation(violation);
            return;
        }
        if (captureAgentMessage && "agentMessage".equals(itemType)) {
            this.extractText(item).ifPresent(active::addAgentMessage);
        }
    }

    private void handleTurnCompleted(final JsonNode params) {
        this.requireObject(params, TURN_COMPLETED + " params");
        final String turnId = this.resolveTurnId(params, TURN_COMPLETED, true);
        final String status = this.resolveStatus(params);
        final ActiveTurn active = this.findActive(params, turnId);
        if (active == null) {
            this.bufferPreRegistrationOnly(turnId, TURN_COMPLETED, params);
            return;
        }
        if (active.done()) {
            return;
        }
        this.complete(active, status);
    }

    private void complete(final ActiveTurn active, final String status) {
        if (status == null) {
            active.fail(this.protocolFailure("Codex turn/completed omitted status"));
            return;
        }
        if ("completed".equals(status)) {
            final Optional<String> output = active.finalAgentMessage();
            if (output.isEmpty() || output.get().isBlank()) {
                active.fail(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
                return;
            }
            active.complete(output.get());
            return;
        }
        if ("failed".equals(status) || "interrupted".equals(status)) {
            active.fail(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
            return;
        }
        active.fail(this.protocolFailure("Codex turn completed with unknown status."));
    }

    private void replayBuffered(final ActiveTurn active) {
        final List<BufferedNotification> buffered = this.bufferedByTurnId.remove(active.key().turnId());
        if (buffered == null) {
            return;
        }
        for (final BufferedNotification notification : buffered) {
            if (FAIL_CLOSED_SERVER_REQUEST.equals(notification.method())) {
                active.failPolicyViolation(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
            } else {
                this.handleNotification(notification.method(), notification.params());
            }
        }
    }

    private void failRequestTurn(final JsonNode params) {
        final String turnId = this.resolveTurnId(params, "server request", false);
        if (turnId == null) {
            final String threadId = this.resolveThreadId(params, false);
            final AgentExecutionException failure = new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.");
            if (threadId != null && this.preRegistrationThreadIds.containsKey(threadId)) {
                this.failedPreRegistrationThreadIds.put(threadId, failure);
                return;
            }
            this.preRegistrationThreadIds.keySet().forEach(preRegistrationThreadId ->
                    this.failedPreRegistrationThreadIds.put(preRegistrationThreadId, failure));
            this.activeByKey.values().forEach(active -> active.failPolicyViolation(failure));
            return;
        }
        final ActiveTurn active = this.findActive(params, turnId);
        if (active == null) {
            if (!this.bufferPreRegistrationOnly(turnId, FAIL_CLOSED_SERVER_REQUEST, params)) {
                this.preRegistrationThreadIds.keySet().forEach(preRegistrationThreadId ->
                        this.failedPreRegistrationThreadIds.put(preRegistrationThreadId, new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.")));
                this.activeByKey.values().forEach(turn -> turn.failPolicyViolation(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.")));
            }
            return;
        }
        if (active.done()) {
            return;
        }
        active.failPolicyViolation(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
    }

    private ActiveTurn findActive(final JsonNode params, final String turnId) {
        final String threadId = this.resolveThreadId(params, false);
        if (threadId != null) {
            return this.activeByKey.get(new CodexTurnKey(threadId, turnId));
        }
        return this.activeByTurnId.get(turnId);
    }

    private boolean bufferPreRegistrationOnly(final String turnId, final String method, final JsonNode params) {
        final String threadId = this.resolveThreadId(params, false);
        if (threadId == null || !this.preRegistrationThreadIds.containsKey(threadId)) {
            return false;
        }
        this.bufferedByTurnId.compute(turnId, (ignored, existing) -> {
            final List<BufferedNotification> next = existing == null ? new ArrayList<>() : existing;
            if (next.size() < MAX_BUFFERED_PRE_REGISTRATION_NOTIFICATIONS) {
                next.add(new BufferedNotification(method, params.deepCopy()));
            } else {
                this.failedPreRegistrationThreadIds.put(threadId, new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
            }
            return next;
        });
        return true;
    }

    private AgentExecutionException itemTypeViolation(final String itemType) {
        if (itemType != null && SAFE_ITEM_TYPES.containsKey(itemType)) {
            return null;
        }
        if (itemType != null && FORBIDDEN_ITEM_TYPES.containsKey(itemType)) {
            return new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.");
        }
        return this.protocolFailure("Codex emitted unknown generation item type.");
    }

    private String resolveThreadId(final JsonNode params, final boolean required) {
        return this.resolveIdentity(
                "threadId",
                required ? "Codex result omitted threadId" : null,
                this.value(params, "threadId"),
                this.value(params.path("thread"), "id"),
                this.value(params.path("thread"), "sessionId")
        );
    }

    String requireThreadId(final JsonNode payload) {
        this.requireObject(payload, "thread/start result");
        return this.resolveThreadId(payload, true);
    }

    String requireTurnId(final JsonNode payload) {
        this.requireObject(payload, "turn/start result");
        return this.resolveTurnId(payload, "turn/start", true);
    }

    private String resolveTurnId(final JsonNode params, final String context, final boolean required) {
        final JsonNode item = params == null ? null : params.path("item");
        return this.resolveIdentity(
                "turnId",
                required ? "Codex " + context + " omitted turnId" : null,
                this.value(params, "turnId"),
                this.value(params == null ? null : params.path("turn"), "id"),
                this.value(item, "turnId"),
                this.value(item == null ? null : item.path("turn"), "id")
        );
    }

    private String resolveStatus(final JsonNode params) {
        return this.resolveIdentity(
                "status",
                "Codex turn/completed omitted status",
                this.value(params, "status"),
                this.value(params.path("turn"), "status")
        );
    }

    private String resolveIdentity(final String label, final String requiredMessage, final String... values) {
        String resolved = null;
        for (final String value : values) {
            if (value == null) {
                continue;
            }
            if (resolved == null) {
                resolved = value;
            } else if (!resolved.equals(value)) {
                throw this.protocolFailure("Codex protocol contained conflicting " + label + " values.");
            }
        }
        if (resolved == null && requiredMessage != null) {
            throw this.protocolFailure(requiredMessage);
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
            throw this.protocolFailure("Codex " + context + " must be an object.");
        }
    }

    private ObjectNode decline() {
        final ObjectNode response = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        response.put("decision", "decline");
        return response;
    }

    private AgentExecutionException protocolFailure(final String message) {
        return new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.");
    }

    record ActiveTurn(CodexTurnKey key, CompletableFuture<String> future, List<String> agentMessages) {

        ActiveTurn(final CodexTurnKey key) {
            this(key, new CompletableFuture<>(), new ArrayList<>());
        }

        synchronized void addAgentMessage(final String message) {
            this.agentMessages.add(message);
        }

        synchronized Optional<String> finalAgentMessage() {
            if (this.agentMessages.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(this.agentMessages.get(this.agentMessages.size() - 1));
        }

        void complete(final String output) {
            this.future.complete(output);
        }

        void fail(final RuntimeException exception) {
            this.future.completeExceptionally(exception);
        }

        void failPolicyViolation(final RuntimeException exception) {
            this.future.completeExceptionally(new PolicyViolationException(exception));
        }

        boolean done() {
            return this.future.isDone();
        }

        String threadId() {
            return this.key.threadId();
        }

        String turnId() {
            return this.key.turnId();
        }
    }

    private record CodexTurnKey(String threadId, String turnId) {
    }

    private record BufferedNotification(String method, JsonNode params) {
    }

    static final class PolicyViolationException extends RuntimeException {

        private PolicyViolationException(final RuntimeException cause) {
            super(cause);
        }
    }
}
