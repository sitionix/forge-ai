package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class CodexExecutionEventObserver {
    private final CodexAgentExecutionEventMapper mapper;
    private final CodexExecutionIdentityCallbacks callbacks;
    private final List<AgentExecutionEventCandidate> pending = new ArrayList<>();
    private String threadId;
    private String turnId;
    private boolean active;
    private boolean completed;

    CodexExecutionEventObserver(final CodexAgentExecutionEventMapper mapper,
                                final CodexExecutionIdentityCallbacks callbacks) {
        this.mapper = mapper;
        this.callbacks = callbacks;
    }

    synchronized void bindThread(final String threadId) {
        this.threadId = threadId;
    }

    synchronized void observe(final String method, final JsonNode params) {
        if (this.callbacks == null || !this.matchesThread(params)) return;
        final String candidateTurn = this.turnIdentity(method, params);
        if (candidateTurn == null || this.turnId != null && !this.turnId.equals(candidateTurn)) return;
        try {
            this.mapper.map(method, params, Instant.now()).ifPresent(candidate -> {
                if (this.active) this.callbacks.executionEvent(candidate);
                else this.pending.add(candidate);
            });
        } catch (final RuntimeException failure) {
            try {
                this.callbacks.eventCaptureDegraded(failure);
            } catch (final RuntimeException ignored) {
                // Observability must never escape into provider notification handling.
            }
        }
    }

    synchronized void activate(final String turnId) {
        if (this.callbacks == null || this.active) return;
        this.turnId = turnId;
        this.active = true;
        this.callbacks.executionEvent(this.mapper.turnStarted(turnId, Instant.now()));
        this.pending.forEach(this.callbacks::executionEvent);
        this.pending.clear();
    }

    synchronized void complete() {
        if (this.callbacks == null || !this.active || this.completed) return;
        this.completed = true;
        this.callbacks.eventCaptureCompleted(this.mapper.turnCompleted(this.turnId, Instant.now()));
    }

    synchronized void discard() {
        this.pending.clear();
    }

    private boolean matchesThread(final JsonNode params) {
        if (this.threadId == null || params == null || !params.isObject()) return false;
        final JsonNode value = params.path("threadId");
        return value.isTextual() && this.threadId.equals(value.asText());
    }

    private String turnIdentity(final String method, final JsonNode params) {
        final JsonNode value = "turn/started".equals(method) || "turn/completed".equals(method)
                ? params.path("turn").path("id") : params.path("turnId");
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
