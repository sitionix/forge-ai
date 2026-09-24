package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import java.util.HashMap;
import java.util.Map;

/** Force termination through native handles never acquires Process stdin/Writer monitors. */
record CodexProcessTree(Process process, ProcessHandle root) {
    private static final int PRE_ROOT_SNAPSHOT_SWEEPS = 3;

    static CodexProcessTree capture(final Process process) {
        try {
            return new CodexProcessTree(process, process.toHandle());
        } catch (UnsupportedOperationException exception) {
            // Synthetic unit-test processes only. Production ProcessBuilder always exposes a native handle.
            return new CodexProcessTree(process, null);
        }
    }

    void terminateTree() {
        if (this.process instanceof ManagedRuntimeProcess managed) {
            managed.terminateOwnedUnit();
            return;
        }
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
        } catch (UnsupportedOperationException ignored) {
            // Root termination still works when descendant enumeration is unsupported.
        }
    }

    private static int depthFromRoot(final ProcessHandle handle, final ProcessHandle root) {
        int depth = 1;
        ProcessHandle ancestor = handle;
        while (depth < 64) {
            final var parent = ancestor.parent();
            if (parent.isEmpty() || parent.get().pid() == root.pid()) return depth;
            ancestor = parent.get();
            depth++;
        }
        return depth;
    }

    private static void terminateChildFirst(final Map<Long, OwnedHandle> descendants) {
        descendants.values().stream().sorted((left, right) -> Integer.compare(right.depth(), left.depth()))
                .map(OwnedHandle::handle).filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private record OwnedHandle(ProcessHandle handle, int depth) { }
}
