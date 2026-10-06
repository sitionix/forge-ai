package com.sitionix.forgeagent.application.llm;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.sitionix.forgeagent.domain.exception.LlmAuthorizationException;

/** Installation-scoped admission and owned cancellation, shared by Agent and Knowledge. */
@Component
public class ForgeCodexAuthorizationGate {
    private final LlmAuthorizationService service;
    private final Map<AuthorizationLease, Boolean> leases = new IdentityHashMap<>();

    public ForgeCodexAuthorizationGate(LlmAuthorizationService service) {
        this.service = service;
        service.registerCoordinatedCleanup(this::cancelAll);
    }

    public AuthorizationLease requireAuthorized() {
        var state = service.verifyAuthorization();
        var lease = new AuthorizationLease(this, state.generation(), true);
        service.admit(state.generation(), () -> {
            synchronized (leases) { leases.put(lease, Boolean.TRUE); }
        });
        return lease;
    }

    /** Owns read-only discovery/recovery processes without granting inference. */
    public AuthorizationLease observe() {
        var lease = new AuthorizationLease(this, service.currentState().generation(), false);
        service.observe(lease.generation, () -> {
            synchronized (leases) { leases.put(lease, Boolean.TRUE); }
        });
        return lease;
    }

    public void registerCancellation(AuthorizationLease lease, Runnable cancellation) {
        requireOwned(lease);
        Objects.requireNonNull(cancellation);
        synchronized (lease) { lease.cleanups.add(cancellation); }
        synchronized (leases) { leases.put(lease, Boolean.TRUE); }
        try { check(lease); }
        catch (RuntimeException revoked) {
            cancel(lease);
            throw revoked;
        }
    }

    public void release(AuthorizationLease lease) {
        requireOwned(lease);
        // Bounded owned cleanup must still run after an interrupted provider wait.
        boolean interrupted = Thread.interrupted();
        try { cancel(lease); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /** Admission is atomic; provider writes and waits never hold the service state lock.
     * Already admitted writes may race revocation; registered cleanup owns their process. */
    public void dispatch(AuthorizationLease lease, Runnable writeRequest) {
        if (!lease.inference) throw new LlmAuthorizationException("CODEX_AUTH_REQUIRED");
        check(lease);
        writeRequest.run();
    }

    public void check(AuthorizationLease lease) {
        requireOwned(lease);
        if (lease.revoked) throw new LlmAuthorizationException("CODEX_AUTH_REQUIRED");
        Runnable check = () -> {
            if (lease.revoked) throw new LlmAuthorizationException("CODEX_AUTH_REQUIRED");
        };
        if (lease.inference) service.admit(lease.generation, check);
        else service.observe(lease.generation, check);
    }

    private void requireOwned(AuthorizationLease lease) {
        if (lease == null || lease.owner != this) throw new IllegalArgumentException("CODEX_AUTH_REQUIRED");
    }

    private void cancelAll() {
        final List<AuthorizationLease> snapshot;
        synchronized (leases) { snapshot = new ArrayList<>(leases.keySet()); }
        boolean failed = false;
        for (var lease : snapshot) {
            try { cancel(lease); }
            catch (RuntimeException failure) { failed = true; }
        }
        if (failed) throw new LlmAuthorizationException("CODEX_AUTH_CLEANUP_FAILED");
    }

    private void cancel(AuthorizationLease lease) {
        final List<Runnable> snapshot;
        synchronized (lease) { lease.revoked = true; snapshot = new ArrayList<>(lease.cleanups); }
        boolean failed = false;
        for (Runnable cleanup : snapshot) {
            try {
                cleanup.run();
                synchronized (lease) { lease.cleanups.remove(cleanup); }
            } catch (RuntimeException failure) { failed = true; }
        }
        if (failed) {
            service.cleanupFailed();
            throw new LlmAuthorizationException("CODEX_AUTH_CLEANUP_FAILED");
        }
        synchronized (lease) {
            if (lease.cleanups.isEmpty()) {
                synchronized (leases) { leases.remove(lease); }
            }
        }
    }

    public static final class AuthorizationLease {
        private final ForgeCodexAuthorizationGate owner;
        private final long generation;
        private final boolean inference;
        private final List<Runnable> cleanups = new ArrayList<>();
        private volatile boolean revoked;
        private AuthorizationLease(ForgeCodexAuthorizationGate owner, long generation, boolean inference) {
            this.owner = owner;
            this.generation = generation;
            this.inference = inference;
        }
        public long generation() { return generation; }
    }
}
