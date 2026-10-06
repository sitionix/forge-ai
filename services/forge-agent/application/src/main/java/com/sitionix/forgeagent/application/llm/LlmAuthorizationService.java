package com.sitionix.forgeagent.application.llm;

import com.sitionix.forgeagent.domain.model.LlmAuthorizationState;
import com.sitionix.forgeagent.domain.exception.LlmAuthorizationException;
import com.sitionix.forgeagent.domain.model.LlmLoginAttempt;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationPort;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationFence;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.DisposableBean;
import static com.sitionix.forgeagent.domain.model.LlmAuthorizationState.AuthState.*;
import static com.sitionix.forgeagent.domain.model.LlmAuthorizationState.Availability.*;
import static com.sitionix.forgeagent.domain.model.LlmLoginAttempt.Status.*;

/** Owns installation authorization, while the gateway owns only provider protocol/processes. */
@Service
public class LlmAuthorizationService implements LlmAuthorizationPort, AutoCloseable, DisposableBean {
    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);
    private final LlmAuthorizationGateway gateway;
    private final Clock clock;
    private final LlmAuthorizationFence fence;
    // Provider operations serialize separately: state invalidation never waits for provider I/O.
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();
    private final Object logoutLock = new Object();
    // Failed coordinated callbacks remain obligations, including on a later default logout.
    // Guarded by logoutLock; never execute these callbacks under stateLock.
    private final List<Runnable> pendingCleanups = new ArrayList<>();
    private final List<Runnable> coordinatedCleanups = new java.util.concurrent.CopyOnWriteArrayList<>();
    private LlmAuthorizationState state = new LlmAuthorizationState("codex", SIGNED_OUT, 0, null, null, AVAILABLE, null);
    private boolean discoveryBlocked;
    private boolean closed;
    private int logoutsInProgress;
    private boolean unresolvedLogout;
    // These fields are accessed only under operationLock.
    private LlmAuthorizationGateway.Session session;
    private LlmLoginAttempt attempt;
    private String owner;
    private String providerLoginId;
    private long attemptGeneration;

    public LlmAuthorizationService(LlmAuthorizationGateway gateway, Clock clock) {
        this(gateway, clock, LlmAuthorizationFence.ephemeral());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LlmAuthorizationService(LlmAuthorizationGateway gateway, Clock clock, LlmAuthorizationFence fence) {
        this.gateway = gateway;
        this.clock = clock;
        this.fence = Objects.requireNonNull(fence);
        try {
            var status = fence.status();
            if (status == LlmAuthorizationFence.Status.BLOCKED) restoreFence("CODEX_LOGOUT_REQUIRED");
            else if (status == LlmAuthorizationFence.Status.UNINITIALIZED) discoveryBlocked = true;
        } catch (RuntimeException failure) { restoreFence("CODEX_AUTH_FENCE_UNAVAILABLE"); }
    }

    private void restoreFence(String code) {
        discoveryBlocked = true;
        unresolvedLogout = true;
        state = new LlmAuthorizationState("codex", ERROR, 0, null, null, AVAILABLE, code);
    }

    @Override public LlmAuthorizationState readAccount() {
        synchronized (operationLock) {
            maintainLocked();
            return read(false);
        }
    }

    public LlmAuthorizationState currentState() { synchronized (stateLock) { return state; } }

    /** Fresh provider verification; Task 3 must fence work admission against the returned generation. */
    public LlmAuthorizationState verifyAuthorization() {
        synchronized (operationLock) {
            maintainLocked();
            return read(true);
        }
    }

    /** Immediate revocation boundary, safe even while a provider request is waiting. */
    public LlmAuthorizationState invalidateAuthorization() {
        synchronized (stateLock) {
            discoveryBlocked = true;
            state = new LlmAuthorizationState("codex", ERROR, state.generation() + 1,
                    null, null, state.availability(), "CODEX_AUTH_REQUIRED");
            return state;
        }
    }

    @Override public LlmLoginAttempt startLogin(String binding) {
        requireBinding(binding);
        synchronized (operationLock) {
            maintainLocked();
            if (pending()) {
                if (!owner.equals(binding)) throw new IllegalStateException("LOGIN_IN_PROGRESS");
                return attempt;
            }
            synchronized (stateLock) {
                if (closed) throw new IllegalStateException("CODEX_AUTH_UNAVAILABLE");
                if (logoutsInProgress > 0) throw new IllegalStateException("CODEX_LOGOUT_IN_PROGRESS");
                if (unresolvedLogout) throw new IllegalStateException("CODEX_LOGOUT_REQUIRED");
                discoveryBlocked = true;
                state = new LlmAuthorizationState("codex", CONNECTING, state.generation() + 1, null, null, AVAILABLE, null);
                attemptGeneration = state.generation();
            }
            owner = binding;
            attempt = new LlmLoginAttempt(UUID.randomUUID(), PENDING, clock.instant().plus(LOGIN_LIFETIME), null, null);
            try {
                blockFence();
                closeSession();
                var login = session().startLogin();
                providerLoginId = login.providerLoginId();
                if (currentState().generation() != attemptGeneration) {
                    finishAttempt(CANCELLED, null);
                    cancelAndClose();
                } else {
                    attempt = new LlmLoginAttempt(attempt.loginId(), PENDING, attempt.expiresAt(), login.authUrl(), null);
                }
            } catch (RuntimeException exception) {
                fail("CODEX_LOGIN_FAILED", attemptGeneration);
            }
            return attempt;
        }
    }

    @Override public LlmLoginAttempt readLogin(UUID id, String binding) {
        synchronized (operationLock) {
            requireOwner(id, binding);
            maintainLocked();
            return attempt;
        }
    }

    @Override public LlmLoginAttempt cancelLogin(UUID id, String binding) {
        synchronized (operationLock) {
            requireOwner(id, binding);
            if (!pending()) return attempt;
            finishAttempt(CANCELLED, null);
            long generation = invalidateAuthorization().generation();
            try {
                cancelAndClose();
                setSignedOut(generation);
            } catch (RuntimeException exception) {
                fail("CODEX_LOGIN_CANCEL_FAILED", generation);
            }
            return attempt;
        }
    }

    /** Register installation-owned lifecycle cleanup; every logout entrypoint enforces it. */
    public void registerCoordinatedCleanup(Runnable cleanup) {
        coordinatedCleanups.add(Objects.requireNonNull(cleanup));
    }

    /** A process attached after revocation can still fail cleanup; retain the logout fence. */
    void cleanupFailed() {
        synchronized (stateLock) {
            unresolvedLogout = true;
            invalidateAuthorization();
            try { fence.block(); } catch (RuntimeException ignored) { /* Keep the in-memory admission fence. */ }
        }
    }

    /** Only in-memory registration belongs in this critical section, never provider I/O. */
    void admit(long generation, Runnable registration) {
        synchronized (stateLock) {
            if (unresolvedLogout || logoutsInProgress > 0)
                throw new LlmAuthorizationException("CODEX_LOGOUT_REQUIRED");
            if (closed || state.authState() != CONNECTED || state.generation() != generation)
                throw new LlmAuthorizationException("CODEX_AUTH_REQUIRED");
            registration.run();
        }
    }

    void observe(long generation, Runnable registration) {
        synchronized (stateLock) {
            if (closed || unresolvedLogout || logoutsInProgress > 0)
                throw new LlmAuthorizationException("CODEX_LOGOUT_REQUIRED");
            if (state.generation() != generation) throw new LlmAuthorizationException("CODEX_AUTH_REQUIRED");
            registration.run();
        }
    }

    @Override public LlmAuthorizationState logout() { return logout(() -> { }); }

    /** Task 3 must install coordinated work cleanup on every public logout path. */
    public LlmAuthorizationState logout(Runnable cleanup) {
        final long generation;
        synchronized (stateLock) {
            logoutsInProgress++;
            unresolvedLogout = true;
            generation = invalidateAuthorization().generation();
        }
        // Persist revocation before waiting for locks or any provider/process cleanup.
        // Even when storage fails, retain cancellation obligations and attempt cleanup.
        boolean durableBlock;
        try { blockFence(); durableBlock = true; }
        catch (RuntimeException failure) { durableBlock = false; }
        try {
            synchronized (logoutLock) {
                for (Runnable coordinated : coordinatedCleanups)
                    if (!pendingCleanups.contains(coordinated)) pendingCleanups.add(coordinated);
                pendingCleanups.add(Objects.requireNonNull(cleanup));
                for (var iterator = pendingCleanups.iterator(); iterator.hasNext();) {
                    try { iterator.next().run(); iterator.remove(); }
                    catch (RuntimeException exception) { /* Keep this obligation for coordinated retry. */ }
                }
                synchronized (operationLock) {
                    if (pending()) finishAttempt(CANCELLED, null);
                    try {
                        cancelAndClose();
                        if (!pendingCleanups.isEmpty()) throw new IllegalStateException("CODEX_LOGOUT_FAILED");
                        session().logout();
                        if (session.readAccount(false).connected()) throw new IllegalStateException("CODEX_LOGOUT_FAILED");
                        closeSession();
                        if (!durableBlock) throw new IllegalStateException("CODEX_AUTH_FENCE_UNAVAILABLE");
                        synchronized (stateLock) {
                            if (state.generation() == generation && logoutsInProgress == 1 && !closed) {
                                fence.clear();
                                unresolvedLogout = false;
                                setSignedOut(generation);
                            }
                        }
                    } catch (RuntimeException exception) {
                        fail("CODEX_LOGOUT_FAILED", generation);
                    }
                    return currentState();
                }
            }
        } finally {
            synchronized (stateLock) { logoutsInProgress--; }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void maintain() { synchronized (operationLock) { maintainLocked(); } }

    private void maintainLocked() {
        if (pending() && (currentState().generation() != attemptGeneration || !clock.instant().isBefore(attempt.expiresAt()))) {
            boolean expired = currentState().generation() == attemptGeneration;
            finishAttempt(expired ? EXPIRED : CANCELLED, null);
            long generation = expired ? invalidateAuthorization().generation() : currentState().generation();
            try {
                cancelAndClose();
                if (expired) setSignedOut(generation);
            } catch (RuntimeException exception) { fail("CODEX_LOGIN_CANCEL_FAILED", generation); }
            return;
        }
        if (session == null) return;
        long generation = currentState().generation();
        try {
            if (!session.healthy()) throw new IllegalStateException("CODEX_AUTH_UNAVAILABLE");
            for (var event : session.drainEvents()) {
                if (event.type() == LlmAuthorizationGateway.EventType.FAILED) {
                    fail("CODEX_AUTH_PROVIDER_ERROR", generation);
                    return;
                }
                if (event.type() == LlmAuthorizationGateway.EventType.LOGIN_COMPLETED && pending()
                        && Objects.equals(providerLoginId, event.providerLoginId())) {
                    if (!event.success()) { fail("CODEX_LOGIN_FAILED", generation); return; }
                    var account = session.readAccount(false);
                    if (!account.connected()) { fail("CODEX_LOGIN_NOT_CONFIRMED", generation); return; }
                    closeSession();
                    synchronized (stateLock) {
                        if (state.generation() == attemptGeneration && !closed && logoutsInProgress == 0 && !unresolvedLogout) {
                            try { fence.clear(); }
                            catch (RuntimeException failure) { unresolvedLogout = true; throw failure; }
                            finishAttempt(COMPLETED, null);
                            applyAccount(account);
                        } else finishAttempt(CANCELLED, null);
                    }
                    return;
                }
                if (event.type() == LlmAuthorizationGateway.EventType.ACCOUNT_UPDATED && !pending()) {
                    read(false);
                    if (session == null) return;
                }
            }
        } catch (RuntimeException exception) { fail("CODEX_AUTH_PROVIDER_ERROR", generation); }
    }

    private LlmAuthorizationState read(boolean refresh) {
        final long generation;
        synchronized (stateLock) {
            if (!readAllowed(refresh)) return state;
            generation = state.generation();
        }
        try {
            var account = session().readAccount(refresh);
            synchronized (stateLock) {
                if (state.generation() == generation && readAllowed(refresh)) applyAccount(account);
            }
        } catch (RuntimeException exception) {
            fail(refresh ? "CODEX_AUTH_VERIFICATION_FAILED" : "CODEX_AUTH_UNAVAILABLE", generation);
        }
        return currentState();
    }

    // Called with stateLock and operationLock held; capture and publication share the same fences.
    private boolean readAllowed(boolean refresh) {
        if (closed || logoutsInProgress > 0 || unresolvedLogout || pending()) return false;
        return refresh ? !(state.authState() == SIGNED_OUT && discoveryBlocked) : !discoveryBlocked;
    }

    private void applyAccount(LlmAuthorizationGateway.Account account) {
        var next = account.connected() ? CONNECTED : SIGNED_OUT;
        boolean changed = state.authState() != next || !Objects.equals(state.email(), account.email()) || !Objects.equals(state.plan(), account.plan());
        state = new LlmAuthorizationState("codex", next, state.generation() + (changed ? 1 : 0),
                account.connected() ? account.email() : null, account.connected() ? account.plan() : null, AVAILABLE, null);
        discoveryBlocked = false;
    }

    private void setSignedOut(long generation) {
        synchronized (stateLock) {
            if (state.generation() == generation) state = new LlmAuthorizationState("codex", SIGNED_OUT, generation, null, null, AVAILABLE, null);
        }
    }

    private void fail(String code, long generation) {
        boolean providerAvailable = session != null && session.healthy();
        synchronized (stateLock) {
            if (state.generation() == generation) {
                discoveryBlocked = true;
                state = new LlmAuthorizationState("codex", ERROR, generation + 1, null, null,
                        providerAvailable ? AVAILABLE : UNAVAILABLE, code);
            }
        }
        if (pending()) {
            // A failed callback may already have written credentials. Only explicit cleanup/new
            // confirmed login can release the durable pending fence, never an inference refresh.
            synchronized (stateLock) { unresolvedLogout = true; }
            finishAttempt(FAILED, code);
        }
        try { closeSession(); } catch (RuntimeException ignored) { /* Remain blocked; never expose provider details. */ }
    }

    private LlmAuthorizationGateway.Session session() {
        if (session == null) session = gateway.openSession();
        return session;
    }

    private void cancelAndClose() {
        try {
            if (session != null && providerLoginId != null) session.cancelLogin(providerLoginId);
        } catch (RuntimeException exception) {
            synchronized (stateLock) { unresolvedLogout = true; }
            throw exception;
        }
        finally { providerLoginId = null; closeSession(); }
    }

    private void closeSession() {
        if (session != null) {
            // Retain the handle if cleanup fails so subsequent logout/shutdown can retry it.
            try { session.close(); }
            catch (RuntimeException exception) {
                synchronized (stateLock) {
                    unresolvedLogout = true;
                    try { fence.block(); } catch (RuntimeException ignored) { /* Stay blocked in memory. */ }
                }
                throw exception;
            }
            session = null;
        }
    }

    private void blockFence() {
        try { fence.block(); }
        catch (RuntimeException failure) {
            synchronized (stateLock) { unresolvedLogout = true; }
            throw failure;
        }
    }

    private boolean pending() { return attempt != null && attempt.status() == PENDING; }
    private void finishAttempt(LlmLoginAttempt.Status status, String code) {
        attempt = new LlmLoginAttempt(attempt.loginId(), status, attempt.expiresAt(), null, code);
    }
    private void requireOwner(UUID id, String binding) {
        if (attempt == null || !attempt.loginId().equals(id) || !Objects.equals(owner, binding))
            throw new IllegalArgumentException("LOGIN_NOT_FOUND");
    }
    private static void requireBinding(String binding) {
        if (binding == null || binding.isBlank() || binding.length() > 256) throw new IllegalArgumentException("INVALID_BROWSER_BINDING");
    }

    @Override public void close() {
        invalidateAuthorization();
        synchronized (stateLock) { closed = true; }
        synchronized (operationLock) {
            if (pending()) finishAttempt(CANCELLED, null);
            cancelAndClose();
        }
    }
    @Override public void destroy() { close(); }
}
