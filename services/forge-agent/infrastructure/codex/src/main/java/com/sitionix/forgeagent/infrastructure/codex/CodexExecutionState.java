package com.sitionix.forgeagent.infrastructure.codex;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class CodexExecutionState {

    private final CodexTurnKey key;
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final List<String> agentMessages = new ArrayList<>();
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
