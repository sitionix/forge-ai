package com.sitionix.forgeagent.infrastructure.codex;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class CodexExecutionState {

    private final CodexTurnKey key;
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private String latestFinalAnswer;
    private String latestCompatibilityAnswer;
    private volatile boolean policyViolation;

    CodexExecutionState(final CodexTurnKey key) {
        this.key = key;
    }

    CodexTurnKey key() {
        return this.key;
    }

    CompletableFuture<String> result() {
        return this.result;
    }

    synchronized void addAgentMessage(final String message, final String phase) {
        if ("final_answer".equals(phase)) {
            this.latestFinalAnswer = message;
            return;
        }
        if (phase == null) {
            this.latestCompatibilityAnswer = message;
        }
    }

    synchronized Optional<String> finalAgentMessage() {
        return Optional.ofNullable(this.latestFinalAnswer == null ? this.latestCompatibilityAnswer : this.latestFinalAnswer);
    }

    void complete(final String output) {
        this.result.complete(output);
    }

    void fail(final RuntimeException exception) {
        this.result.completeExceptionally(exception);
    }

    void failPolicyViolation(final RuntimeException exception) {
        this.policyViolation = true;
        this.fail(exception);
    }

    boolean policyViolation() {
        return this.policyViolation;
    }

    boolean done() {
        return this.result.isDone();
    }

    String threadId() {
        return this.key.threadId();
    }

    String turnId() {
        return this.key.turnId();
    }
}
