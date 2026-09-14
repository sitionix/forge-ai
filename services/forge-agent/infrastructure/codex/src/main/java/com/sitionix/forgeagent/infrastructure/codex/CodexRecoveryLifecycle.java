package com.sitionix.forgeagent.infrastructure.codex;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Linearizable ownership for aborting a recovery process, including one registered after cancellation. */
final class CodexRecoveryLifecycle {

    private static final int PRE_ROOT_SNAPSHOT_SWEEPS = 3;

    private final AtomicReference<OwnedProcess> process = new AtomicReference<>();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final AtomicBoolean forceIssued = new AtomicBoolean();

    boolean register(final Process candidate) {
        Objects.requireNonNull(candidate, "candidate");
        final OwnedProcess owned = OwnedProcess.capture(candidate);
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
        final OwnedProcess registered = this.process.get();
        if (registered != null && this.forceIssued.compareAndSet(false, true)) {
            registered.terminateTree();
        }
    }

    private record OwnedProcess(Process process, ProcessHandle root) {

        private static OwnedProcess capture(final Process process) {
            try {
                return new OwnedProcess(process, process.toHandle());
            } catch (final UnsupportedOperationException exception) {
                // Test-compatible fallback for synthetic Process implementations. The production ProcessBuilder
                // implementation always exposes a native ProcessHandle and never uses this stream-closing path.
                return new OwnedProcess(process, null);
            }
        }

        private void terminateTree() {
            if (this.root == null) {
                this.process.destroyForcibly();
                return;
            }
            final Map<Long, OwnedHandle> descendants = new HashMap<>();
            for (int sweep = 0; sweep < PRE_ROOT_SNAPSHOT_SWEEPS && this.root.isAlive(); sweep++) {
                this.captureDescendants(descendants);
                terminateChildFirst(descendants);
            }
            this.root.destroyForcibly();
            terminateChildFirst(descendants);
        }

        private void captureDescendants(final Map<Long, OwnedHandle> descendants) {
            try {
                this.root.descendants().forEach(handle -> descendants.merge(
                        handle.pid(), new OwnedHandle(handle, depthFromRoot(handle, this.root)),
                        (known, discovered) -> known.depth() >= discovered.depth() ? known : discovered));
            } catch (final UnsupportedOperationException ignored) {
                // A native root without descendant enumeration can still be terminated without touching its streams.
            }
        }

        private static int depthFromRoot(final ProcessHandle handle, final ProcessHandle root) {
            int depth = 1;
            ProcessHandle ancestor = handle;
            while (depth < 64) {
                final var parent = ancestor.parent();
                if (parent.isEmpty() || parent.get().pid() == root.pid()) {
                    return depth;
                }
                ancestor = parent.get();
                depth++;
            }
            return depth;
        }

        private static void terminateChildFirst(final Map<Long, OwnedHandle> descendants) {
            descendants.values().stream()
                    .sorted((left, right) -> Integer.compare(right.depth(), left.depth()))
                    .map(OwnedHandle::handle)
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
        }
    }

    private record OwnedHandle(ProcessHandle handle, int depth) {
    }
}
