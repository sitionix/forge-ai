package com.sitionix.forgeagent.infrastructure.codex;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Linearizable ownership for aborting a recovery process, including one registered after cancellation. */
final class CodexRecoveryLifecycle {

    private final AtomicReference<CodexProcessTree> process = new AtomicReference<>();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final AtomicBoolean forceIssued = new AtomicBoolean();

    boolean register(final Process candidate) {
        Objects.requireNonNull(candidate, "candidate");
        final CodexProcessTree owned = CodexProcessTree.capture(candidate);
        if (!this.process.compareAndSet(null, owned)) {
            throw new IllegalStateException("Codex recovery process was already registered");
        }
        if (this.aborted.get()) {
            this.forceRegisteredProcess();
        }
        return !this.aborted.get();
    }

    boolean aborted() {
        return this.aborted.get();
    }

    void abort() {
        this.aborted.set(true);
        this.forceRegisteredProcess();
    }

    private void forceRegisteredProcess() {
        final CodexProcessTree registered = this.process.get();
        if (registered != null && this.forceIssued.compareAndSet(false, true)) {
            registered.terminateTree();
        }
    }

}
