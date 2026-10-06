package com.sitionix.forgeagent.application.llm;

import com.sitionix.forgeagent.domain.model.LlmAuthorizationState;
import com.sitionix.forgeagent.domain.model.LlmLoginAttempt;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationFence;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LlmAuthorizationServiceTest {
    private final MutableClock clock = new MutableClock();
    private final FakeGateway gateway = new FakeGateway();
    private final LlmAuthorizationFence fence = LlmAuthorizationFence.ephemeral();
    private final LlmAuthorizationService service = new LlmAuthorizationService(gateway, clock, fence);

    @Test void signed_out_account_is_not_connected() {
        var state = service.readAccount();
        assertThat(state.authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        assertThat(state.providerId()).isEqualTo("codex");
        assertThat(gateway.session.refreshes).containsExactly(false);
    }

    @Test void completed_login_requires_account_confirmation() {
        var attempt = service.startLogin("owner");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", true));
        assertThat(service.readLogin(attempt.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.FAILED);
        assertThat(service.readAccount().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(gateway.session.closed).isTrue();
    }

    @Test void confirmed_completion_connects_and_removes_auth_url() {
        var attempt = service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", true));
        var completed = service.readLogin(attempt.loginId(), "owner");
        assertThat(completed.status()).isEqualTo(LlmLoginAttempt.Status.COMPLETED);
        assertThat(completed.authUrl()).isNull();
        assertThat(service.readAccount().authState()).isEqualTo(LlmAuthorizationState.AuthState.CONNECTED);
    }

    @Test void duplicate_start_same_binding_reuses_attempt() {
        var first = service.startLogin("owner");
        assertThat(service.startLogin("owner")).isEqualTo(first);
        assertThat(gateway.session.starts).isEqualTo(1);
        assertThatThrownBy(() -> service.startLogin("other")).hasMessage("LOGIN_IN_PROGRESS");
        assertThat(first.toString()).doesNotContain("state=secret", "https:");
    }

    @Test void different_binding_cannot_read_attempt() {
        var attempt = service.startLogin("owner");
        assertThatThrownBy(() -> service.readLogin(attempt.loginId(), "other")).hasMessage("LOGIN_NOT_FOUND");
        assertThatThrownBy(() -> service.cancelLogin(attempt.loginId(), "other")).hasMessage("LOGIN_NOT_FOUND");
        assertThat(service.readLogin(attempt.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.PENDING);
    }

    @Test void cancelled_completion_cannot_connect() {
        var attempt = service.startLogin("owner");
        service.cancelLogin(attempt.loginId(), "owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "late@example.com", "plus");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", true));
        assertThat(service.readLogin(attempt.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.CANCELLED);
        assertThat(service.readAccount().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        assertThat(gateway.session.closed).isTrue();
    }

    @Test void expired_attempt_stops_owned_process() {
        var attempt = service.startLogin("owner");
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        service.maintain();
        assertThat(service.readLogin(attempt.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.EXPIRED);
        assertThat(gateway.session.cancelled).isTrue();
        assertThat(gateway.session.closed).isTrue();
    }

    @Test void restart_reads_account_not_pending_attempt() {
        var attempt = service.startLogin("owner");
        var restarted = new LlmAuthorizationService(new FakeGateway(), clock);
        assertThat(restarted.readAccount().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        assertThatThrownBy(() -> restarted.readLogin(attempt.loginId(), "owner")).hasMessage("LOGIN_NOT_FOUND");
        service.close();
        assertThat(gateway.session.closed).isTrue();
    }

    @Test void restart_after_cancellation_cannot_reactivate_persisted_account() {
        var attempt = service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "late@example.test", "plus");
        service.cancelLogin(attempt.loginId(), "owner");
        var restarted = new LlmAuthorizationService(gateway, clock, fence);
        assertThat(restarted.verifyAuthorization().authState()).isNotEqualTo(LlmAuthorizationState.AuthState.CONNECTED);
    }

    @Test void restart_during_pending_login_blocks_already_persisted_callback_account() {
        service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "callback@example.test", "plus");
        assertRestartBlocked();
    }

    @Test void restart_after_expired_login_blocks_already_persisted_callback_account() {
        service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "callback@example.test", "plus");
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        service.maintain();
        assertRestartBlocked();
    }

    @Test void restart_after_failed_logout_stays_blocked_until_verified_cleanup() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "fixture@example.test", "plus");
        service.readAccount();
        gateway.session.failLogout = true;
        assertThat(service.logout().errorCode()).isEqualTo("CODEX_LOGOUT_FAILED");
        assertRestartBlocked();
        gateway.session.failLogout = false;
        var restarted = new LlmAuthorizationService(gateway, clock, fence);
        assertThat(restarted.logout().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        assertThat(new LlmAuthorizationService(gateway, clock, fence).readAccount().authState())
                .isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
    }

    @Test void confirmed_login_remains_authorized_after_restart() {
        completeNewLoginAfterRecovery();
        var restarted = new LlmAuthorizationService(gateway, clock, fence);
        assertThat(restarted.verifyAuthorization().authState()).isEqualTo(LlmAuthorizationState.AuthState.CONNECTED);
    }

    @Test void missing_approval_never_adopts_existing_provider_account_but_allows_explicit_login() {
        var uninitialized = new TestFence();
        uninitialized.status = LlmAuthorizationFence.Status.UNINITIALIZED;
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "existing@example.test", "plus");
        var fresh = new LlmAuthorizationService(gateway, clock, uninitialized);
        assertThatThrownBy(() -> new ForgeCodexAuthorizationGate(fresh).requireAuthorized()).hasMessage("CODEX_AUTH_REQUIRED");
        assertThat(gateway.session.refreshes).isEmpty();
        assertThat(fresh.startLogin("owner").status()).isEqualTo(LlmLoginAttempt.Status.PENDING);
    }

    @Test void fence_write_failure_does_not_start_login_or_allow_fresh_account_adoption() {
        var broken = new TestFence();
        broken.failWrite = true;
        var fresh = new LlmAuthorizationService(gateway, clock, broken);
        assertThat(fresh.startLogin("owner").status()).isEqualTo(LlmLoginAttempt.Status.FAILED);
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "existing@example.test", "plus");
        assertThat(gateway.session.starts).isZero();
        assertThatThrownBy(() -> new ForgeCodexAuthorizationGate(fresh).requireAuthorized()).hasMessage("CODEX_LOGOUT_REQUIRED");
    }

    @Test void approval_write_failure_does_not_complete_login() {
        var broken = new TestFence();
        var fresh = new LlmAuthorizationService(gateway, clock, broken);
        var login = fresh.startLogin("owner");
        broken.failWrite = true;
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "fixture@example.test", "plus");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", true));
        assertThat(fresh.readLogin(login.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.FAILED);
        assertThatThrownBy(() -> new ForgeCodexAuthorizationGate(fresh).requireAuthorized()).hasMessage("CODEX_LOGOUT_REQUIRED");
        assertThat(new LlmAuthorizationService(gateway, clock, broken).verifyAuthorization().authState())
                .isNotEqualTo(LlmAuthorizationState.AuthState.CONNECTED);
    }

    @Test void failed_callback_cannot_authorize_from_already_persisted_account() {
        var login = service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "fixture@example.test", "plus");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", false));
        assertThat(service.readLogin(login.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.FAILED);
        assertThatThrownBy(() -> new ForgeCodexAuthorizationGate(service).requireAuthorized()).hasMessage("CODEX_LOGOUT_REQUIRED");
        assertRestartBlocked();
    }

    @Test void logout_persists_revocation_before_waiting_for_coordinated_cleanup() throws Exception {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "fixture@example.test", "plus");
        service.readAccount();
        var cleanupStarted = new CountDownLatch(1);
        var finishCleanup = new CountDownLatch(1);
        var logout = CompletableFuture.supplyAsync(() -> service.logout(() -> {
            cleanupStarted.countDown();
            try { if (!finishCleanup.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException failure) { throw new IllegalStateException(failure); }
        }));
        try {
            assertThat(cleanupStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertRestartBlocked();
        } finally { finishCleanup.countDown(); }
        assertThat(logout.get(2, TimeUnit.SECONDS).authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
    }

    @Test void failed_login_confirmation_cannot_adopt_a_late_account() {
        var login = service.startLogin("owner");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", true));
        assertThat(service.readLogin(login.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.FAILED);
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "late@example.test", "plus");
        assertThatThrownBy(() -> new ForgeCodexAuthorizationGate(service).requireAuthorized()).hasMessage("CODEX_LOGOUT_REQUIRED");
        assertRestartBlocked();
    }

    @Test void cleanup_failure_of_an_approved_account_is_persisted() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "fixture@example.test", "plus");
        service.readAccount();
        gateway.session.failRefresh = true;
        gateway.session.failClose = true;
        service.verifyAuthorization();
        gateway.session.failRefresh = false;
        gateway.session.failClose = false;
        assertRestartBlocked();
    }

    @Test void logout_storage_failure_still_attempts_provider_cleanup_without_claiming_success() {
        var broken = new TestFence();
        var fresh = new LlmAuthorizationService(gateway, clock, broken);
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "fixture@example.test", "plus");
        fresh.readAccount();
        broken.failWrite = true;
        var callbacks = new AtomicInteger();
        assertThat(fresh.logout(callbacks::incrementAndGet).errorCode()).isEqualTo("CODEX_LOGOUT_FAILED");
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(gateway.session.loggedOut).isTrue();
        assertThatThrownBy(() -> new ForgeCodexAuthorizationGate(fresh).requireAuthorized()).hasMessage("CODEX_LOGOUT_REQUIRED");
    }

    private void assertRestartBlocked() {
        var restarted = new LlmAuthorizationService(gateway, clock, fence);
        int reads = gateway.session.refreshes.size();
        assertThatThrownBy(() -> new ForgeCodexAuthorizationGate(restarted).requireAuthorized()).hasMessage("CODEX_LOGOUT_REQUIRED");
        assertThat(gateway.session.refreshes).hasSize(reads);
    }

    static final class TestFence implements LlmAuthorizationFence {
        Status status = Status.APPROVED;
        boolean failWrite;
        public Status status() { return status; }
        public void block() { if (failWrite) throw new IllegalStateException(); status = Status.BLOCKED; }
        public void clear() { if (failWrite) throw new IllegalStateException(); status = Status.APPROVED; }
    }

    @Test void refresh_failure_invalidates_connected_state() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        long previous = service.readAccount().generation();
        gateway.session.failRefresh = true;
        var state = service.verifyAuthorization();
        assertThat(state.authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(state.generation()).isGreaterThan(previous);
        assertThat(state.errorCode()).isEqualTo("CODEX_AUTH_VERIFICATION_FAILED");
        assertThat(service.readAccount().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(state.toString()).doesNotContain("private-token");
    }

    @Test void logout_blocks_before_cleanup_and_failure_stays_blocked() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        long previous = service.readAccount().generation();
        var result = service.logout(() -> {
            assertThat(service.currentState().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
            assertThat(service.currentState().generation()).isGreaterThan(previous);
            throw new IllegalStateException("private-token");
        });
        assertThat(result.errorCode()).isEqualTo("CODEX_LOGOUT_FAILED");
        assertThat(service.readAccount().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
    }

    @Test void logout_verifies_signed_out_state() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        service.readAccount();
        assertThat(service.logout().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        assertThat(gateway.session.loggedOut).isTrue();
        assertThat(gateway.session.closed).isTrue();
    }

    @Test void pending_refresh_cannot_restore_connected_after_immediate_invalidation() throws Exception {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        service.readAccount();
        gateway.session.refreshStarted = new CountDownLatch(1);
        gateway.session.releaseRefresh = new CountDownLatch(1);
        var pending = CompletableFuture.supplyAsync(service::verifyAuthorization);
        assertThat(gateway.session.refreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
        var revoked = CompletableFuture.supplyAsync(service::invalidateAuthorization).get(1, TimeUnit.SECONDS);
        assertThat(revoked.authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        gateway.session.releaseRefresh.countDown();
        assertThat(pending.get(2, TimeUnit.SECONDS)).isEqualTo(revoked);
        assertThat(service.readAccount()).isEqualTo(revoked);
    }

    @Test void logout_blocks_verification_and_new_login_while_cleanup_waits() throws Exception {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        service.readAccount();
        var cleanupStarted = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var logout = CompletableFuture.supplyAsync(() -> service.logout(() -> {
            cleanupStarted.countDown();
            try { releaseCleanup.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { throw new IllegalStateException(exception); }
        }));
        try {
            assertThat(cleanupStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(service.verifyAuthorization().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
            assertThatThrownBy(() -> service.startLogin("owner")).hasMessage("CODEX_LOGOUT_IN_PROGRESS");
        } finally { releaseCleanup.countDown(); }
        assertThat(logout.get(2, TimeUnit.SECONDS).authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
    }

    @Test void process_failure_invalidates_connected_state_and_advances_generation() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        long previous = service.readAccount().generation();
        gateway.session.closed = true;
        service.maintain();
        assertThat(service.currentState().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(service.currentState().generation()).isGreaterThan(previous);
    }

    @Test void unrelated_completion_does_not_complete_pending_login() {
        var attempt = service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("other-id", true));
        gateway.session.events.add(LlmAuthorizationGateway.Event.updated());
        assertThat(service.readLogin(attempt.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.PENDING);
    }

    @Test void browser_denial_is_distinct_from_provider_unavailability() {
        var attempt = service.startLogin("owner");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", false));
        assertThat(service.readLogin(attempt.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.FAILED);
        assertThat(service.currentState().availability()).isEqualTo(LlmAuthorizationState.Availability.AVAILABLE);
        assertThat(service.currentState().errorCode()).isEqualTo("CODEX_LOGIN_FAILED");
    }

    @Test void cleanup_failure_retains_process_for_retry_and_never_claims_signed_out() {
        var attempt = service.startLogin("owner");
        gateway.session.failClose = true;
        service.cancelLogin(attempt.loginId(), "owner");
        assertThat(service.currentState().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(gateway.session.closed).isFalse();
        gateway.session.failClose = false;
        assertThat(service.logout().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        assertThat(gateway.session.closed).isTrue();
    }

    @Test void cached_read_cannot_capture_revoked_generation_after_eligibility_check() {
        assertSnapshotRevocationCannotConnect(false);
    }

    @Test void fresh_read_cannot_capture_revoked_generation_after_eligibility_check() {
        assertSnapshotRevocationCannotConnect(true);
    }

    private void assertSnapshotRevocationCannotConnect(boolean refresh) {
        // Interpose revocation exactly at the pre-fix generation snapshot boundary.
        // The revised read path must decide eligibility and take that snapshot atomically.
        var subject = new RevokingSnapshotService(gateway, clock);
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        subject.beforeSnapshot = subject::invalidateAuthorization;
        // Once capture is moved into the eligibility lock, the old public-snapshot call
        // disappears. The provider boundary then supplies the same one-shot revocation.
        gateway.session.beforeRead = subject::revokeOnce;
        var result = refresh ? subject.verifyAuthorization() : subject.readAccount();
        assertThat(result.authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(subject.currentState()).isEqualTo(result);
        assertThat(gateway.session.refreshes).containsExactly(refresh);
    }

    @Test void pending_cached_read_cannot_restore_connected_after_immediate_invalidation() throws Exception {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        service.readAccount();
        gateway.session.blockCached = true;
        gateway.session.refreshStarted = new CountDownLatch(1);
        gateway.session.releaseRefresh = new CountDownLatch(1);
        var pending = CompletableFuture.supplyAsync(service::readAccount);
        try {
            assertThat(gateway.session.refreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var revoked = CompletableFuture.supplyAsync(service::invalidateAuthorization).get(1, TimeUnit.SECONDS);
            gateway.session.releaseRefresh.countDown();
            assertThat(pending.get(2, TimeUnit.SECONDS)).isEqualTo(revoked);
        } finally { gateway.session.releaseRefresh.countDown(); }
    }

    @Test void failed_coordinated_cleanup_blocks_reads_and_login_until_that_cleanup_succeeds() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        service.readAccount();
        var failCleanup = new AtomicBoolean(true);
        var cleanupCalls = new AtomicInteger();
        Runnable cleanup = () -> {
            cleanupCalls.incrementAndGet();
            if (failCleanup.get()) throw new IllegalStateException("private-token");
        };
        assertThat(service.logout(cleanup).errorCode()).isEqualTo("CODEX_LOGOUT_FAILED");
        int reads = gateway.session.refreshes.size();
        assertThat(service.verifyAuthorization().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(service.readAccount().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(gateway.session.refreshes).hasSize(reads);
        assertThatThrownBy(() -> service.startLogin("owner")).hasMessage("CODEX_LOGOUT_REQUIRED");
        // A default/no-op logout cannot discard the unresolved coordinated cleanup.
        assertThat(service.logout().errorCode()).isEqualTo("CODEX_LOGOUT_FAILED");
        assertThat(cleanupCalls.get()).isEqualTo(2);
        assertThat(gateway.session.loggedOut).isFalse();
        failCleanup.set(false);
        assertThat(service.logout().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        assertThat(cleanupCalls.get()).isEqualTo(3);
        assertThat(gateway.session.loggedOut).isTrue();
        completeNewLoginAfterRecovery();
    }

    @Test void failed_provider_logout_blocks_fresh_verification_until_confirmed_logout_retry() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        service.readAccount();
        gateway.session.failLogout = true;
        assertThat(service.logout().errorCode()).isEqualTo("CODEX_LOGOUT_FAILED");
        int reads = gateway.session.refreshes.size();
        assertThat(service.verifyAuthorization().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(gateway.session.refreshes).hasSize(reads);
        assertThatThrownBy(() -> service.startLogin("owner")).hasMessage("CODEX_LOGOUT_REQUIRED");
        gateway.session.failLogout = false;
        assertThat(service.logout().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
        completeNewLoginAfterRecovery();
    }

    @Test void unresolved_process_cleanup_blocks_fresh_verification_even_after_process_recovers() {
        var attempt = service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        gateway.session.failClose = true;
        service.cancelLogin(attempt.loginId(), "owner");
        gateway.session.failClose = false;
        int reads = gateway.session.refreshes.size();
        assertThat(service.verifyAuthorization().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        assertThat(gateway.session.refreshes).hasSize(reads);
        assertThat(service.logout().authState()).isEqualTo(LlmAuthorizationState.AuthState.SIGNED_OUT);
    }

    @Test void transient_refresh_failure_can_recover_when_no_logout_cleanup_is_unresolved() {
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        service.readAccount();
        gateway.session.failRefresh = true;
        assertThat(service.verifyAuthorization().authState()).isEqualTo(LlmAuthorizationState.AuthState.ERROR);
        gateway.session.failRefresh = false;
        assertThat(service.verifyAuthorization().authState()).isEqualTo(LlmAuthorizationState.AuthState.CONNECTED);
    }

    private void completeNewLoginAfterRecovery() {
        var attempt = service.startLogin("owner");
        gateway.session.account = new LlmAuthorizationGateway.Account(true, "operator@example.com", "plus");
        gateway.session.events.add(LlmAuthorizationGateway.Event.completed("provider-login", true));
        assertThat(service.readLogin(attempt.loginId(), "owner").status()).isEqualTo(LlmLoginAttempt.Status.COMPLETED);
        assertThat(service.currentState().authState()).isEqualTo(LlmAuthorizationState.AuthState.CONNECTED);
    }

    static final class RevokingSnapshotService extends LlmAuthorizationService {
        Runnable beforeSnapshot;
        RevokingSnapshotService(LlmAuthorizationGateway gateway, Clock clock) { super(gateway, clock); }
        void revokeOnce() {
            var action = beforeSnapshot;
            beforeSnapshot = null;
            if (action != null) action.run();
        }
        @Override public LlmAuthorizationState currentState() {
            revokeOnce();
            return super.currentState();
        }
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    static final class FakeGateway implements LlmAuthorizationGateway {
        final FakeSession session = new FakeSession();
        public Session openSession() { session.closed = false; return session; }
    }

    static final class FakeSession implements LlmAuthorizationGateway.Session {
        LlmAuthorizationGateway.Account account = new LlmAuthorizationGateway.Account(false, null, null);
        final List<LlmAuthorizationGateway.Event> events = new ArrayList<>();
        final List<Boolean> refreshes = new ArrayList<>();
        boolean closed, cancelled, loggedOut, failRefresh, failClose, failLogout, blockCached;
        int starts;
        CountDownLatch refreshStarted, releaseRefresh;
        Runnable beforeRead;
        public LlmAuthorizationGateway.Account readAccount(boolean refreshToken) {
            refreshes.add(refreshToken);
            if (beforeRead != null) beforeRead.run();
            if ((refreshToken || blockCached) && refreshStarted != null) {
                refreshStarted.countDown();
                try { if (!releaseRefresh.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            }
            if (refreshToken && failRefresh) throw new IllegalStateException("private-token");
            return account;
        }
        public LlmAuthorizationGateway.Login startLogin() {
            starts++;
            return new LlmAuthorizationGateway.Login("provider-login", "https://auth.openai.com/oauth/authorize?state=secret");
        }
        public void cancelLogin(String id) { cancelled = true; }
        public void logout() {
            if (failLogout) throw new IllegalStateException("private-token");
            loggedOut = true;
            account = new LlmAuthorizationGateway.Account(false, null, null);
        }
        public List<LlmAuthorizationGateway.Event> drainEvents() { var result = List.copyOf(events); events.clear(); return result; }
        public boolean healthy() { return !closed; }
        public void close() { if (failClose) throw new IllegalStateException("private-token"); closed = true; }
    }
}
