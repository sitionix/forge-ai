package com.sitionix.forgeagent.infrastructure.codex;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class CodexExecutionState {

    private final String threadId;
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private volatile String turnId;
    private String latestFinalAnswer;
    private String latestCompatibilityAnswer;
    private volatile boolean providerInterruptRequired;

    CodexExecutionState(final String threadId) {
        this.threadId = threadId;
    }

    String threadId() {
        return this.threadId;
    }

    String turnId() {
        return this.turnId;
    }

    boolean hasTurnId() {
        return this.turnId != null;
    }

    CompletableFuture<String> result() {
        return this.result;
    }

    void bindTurnId(final String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new CodexTransportException("Codex turn id was missing");
        }
        if (this.turnId == null) {
            this.turnId = candidate;
            return;
        }
        if (!this.turnId.equals(candidate)) {
            this.fail(new CodexTransportException("Codex turn id changed for active thread"));
        }
    }

    void addAgentMessage(final String message, final String phase) {
        if ("final_answer".equals(phase)) {
            this.latestFinalAnswer = message;
            return;
        }
        if (phase == null) {
            this.latestCompatibilityAnswer = message;
        }
    }

    Optional<String> finalAgentMessage() {
        return Optional.ofNullable(this.latestFinalAnswer == null ? this.latestCompatibilityAnswer : this.latestFinalAnswer);
    }

    void complete(final String output) {
        this.result.complete(output);
    }

    void fail(final RuntimeException exception) {
        this.result.completeExceptionally(exception);
    }

    void failPolicyViolation(final RuntimeException exception) {
        this.providerInterruptRequired = true;
        this.fail(exception);
    }

    boolean providerInterruptRequired() {
        return this.providerInterruptRequired;
    }

    boolean done() {
        return this.result.isDone();
    }
}
