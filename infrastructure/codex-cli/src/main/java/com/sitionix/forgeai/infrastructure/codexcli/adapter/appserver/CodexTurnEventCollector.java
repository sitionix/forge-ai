package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class CodexTurnEventCollector {

    private final Map<TurnKey, TurnAggregation> turns = new ConcurrentHashMap<>();
    private final Object monitor = new Object();

    void registerTurnStarted(final String threadId, final String turnId, final JsonNode turnNode) {
        if (threadId == null || turnId == null) {
            return;
        }
        final TurnKey key = new TurnKey(threadId, turnId);
        this.turns.computeIfAbsent(key, ignored -> new TurnAggregation()).registerTurnStarted(turnNode);
        synchronized (this.monitor) {
            this.monitor.notifyAll();
        }
    }

    void touch(final String threadId, final String turnId) {
        if (threadId == null || turnId == null) {
            return;
        }
        final TurnKey key = new TurnKey(threadId, turnId);
        this.turns.computeIfAbsent(key, ignored -> new TurnAggregation()).touch();
        synchronized (this.monitor) {
            this.monitor.notifyAll();
        }
    }

    void registerCompletedItem(final String threadId, final String turnId, final JsonNode itemNode) {
        if (threadId == null || turnId == null || itemNode == null) {
            return;
        }
        final TurnKey key = new TurnKey(threadId, turnId);
        this.turns.computeIfAbsent(key, ignored -> new TurnAggregation()).registerItem(itemNode);
        synchronized (this.monitor) {
            this.monitor.notifyAll();
        }
    }

    void registerCompletedTurn(final String threadId, final String turnId, final JsonNode turnNode) {
        if (threadId == null || turnId == null || turnNode == null) {
            return;
        }
        final TurnKey key = new TurnKey(threadId, turnId);
        this.turns.computeIfAbsent(key, ignored -> new TurnAggregation()).registerTurn(turnNode);
        synchronized (this.monitor) {
            this.monitor.notifyAll();
        }
    }

    CompletedTurn awaitCompletedTurn(final String threadId, final String turnId, final Duration timeout) {
        final TurnKey key = new TurnKey(threadId, turnId);
        final long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this.monitor) {
            while (!this.turns.containsKey(key) || !this.turns.get(key).isCompleted()) {
                final long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new IllegalStateException("Timed out waiting for completed Codex threadId=" + threadId + ", turnId=" + turnId);
                }
                final long waitMillis = Math.max(1L, Math.min(remainingNanos / 1_000_000L, 250L));
                try {
                    this.monitor.wait(waitMillis);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Codex threadId=" + threadId + ", turnId=" + turnId, e);
                }
            }
        }
        final TurnAggregation aggregation = Objects.requireNonNull(this.turns.get(key));
        return new CompletedTurn(
                aggregation.completedTurn(),
                aggregation.assistantResponse(),
                aggregation.lastEventAt()
        );
    }

    TurnSnapshot snapshot(final String threadId, final String turnId) {
        final TurnAggregation aggregation = this.turns.get(new TurnKey(threadId, turnId));
        if (aggregation == null) {
            return new TurnSnapshot(false, null);
        }
        return new TurnSnapshot(aggregation.isCompleted(), aggregation.lastEventAt());
    }

    void awaitUpdate(final Duration duration) {
        synchronized (this.monitor) {
            try {
                this.monitor.wait(Math.max(1L, duration.toMillis()));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Codex event update", e);
            }
        }
    }

    void clearThread(final String threadId) {
        this.turns.keySet().removeIf(key -> Objects.equals(key.threadId(), threadId));
    }

    record CompletedTurn(JsonNode turn, String assistantResponse, Instant lastEventAt) {
    }

    record TurnSnapshot(boolean completed, Instant lastEventAt) {
    }

    private record TurnKey(String threadId, String turnId) {
    }

    private static final class TurnAggregation {

        private final StringBuilder assistantResponse = new StringBuilder();
        private JsonNode completedTurn;
        private Instant lastEventAt = Instant.now();

        void registerTurnStarted(final JsonNode turnNode) {
            this.touch();
            if (turnNode != null && turnNode.isObject() && this.completedTurn == null) {
                this.completedTurn = null;
            }
        }

        void registerItem(final JsonNode itemNode) {
            this.touch();
            if (!"agentMessage".equals(itemNode.path("type").asText())) {
                return;
            }
            final String text = itemNode.path("text").asText("");
            if (text.isBlank()) {
                return;
            }
            if (this.assistantResponse.length() > 0) {
                this.assistantResponse.append("\n\n");
            }
            this.assistantResponse.append(text);
        }

        void registerTurn(final JsonNode turnNode) {
            this.touch();
            this.completedTurn = turnNode;
        }

        void touch() {
            this.lastEventAt = Instant.now();
        }

        boolean isCompleted() {
            return this.completedTurn != null;
        }

        JsonNode completedTurn() {
            return this.completedTurn;
        }

        String assistantResponse() {
            return this.assistantResponse.toString();
        }

        Instant lastEventAt() {
            return this.lastEventAt;
        }
    }
}
