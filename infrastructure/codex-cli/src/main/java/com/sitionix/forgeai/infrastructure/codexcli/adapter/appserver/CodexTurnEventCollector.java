package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class CodexTurnEventCollector {

    private final Map<String, JsonNode> completedTurnsById = new ConcurrentHashMap<>();
    private final Object monitor = new Object();

    void registerCompletedTurn(final String turnId, final JsonNode turnNode) {
        if (turnId == null || turnNode == null) {
            return;
        }
        this.completedTurnsById.put(turnId, turnNode);
        synchronized (this.monitor) {
            this.monitor.notifyAll();
        }
    }

    JsonNode awaitCompletedTurn(final String turnId, final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this.monitor) {
            while (!this.completedTurnsById.containsKey(turnId)) {
                final long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new IllegalStateException("Timed out waiting for completed Codex turnId=" + turnId);
                }
                final long waitMillis = Math.max(1L, Math.min(remainingNanos / 1_000_000L, 250L));
                try {
                    this.monitor.wait(waitMillis);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Codex turnId=" + turnId, e);
                }
            }
        }
        return Objects.requireNonNull(this.completedTurnsById.get(turnId));
    }
}
