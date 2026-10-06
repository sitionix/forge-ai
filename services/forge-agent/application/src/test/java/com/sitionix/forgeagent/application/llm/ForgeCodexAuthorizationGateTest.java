package com.sitionix.forgeagent.application.llm;

import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ForgeCodexAuthorizationGateTest {
    private final AccountSession session = new AccountSession();
    private final LlmAuthorizationService service = new LlmAuthorizationService(() -> session, Clock.systemUTC());

    @Test void terminal_login_and_env_key_do_not_satisfy_gate() {
        var gate = new ForgeCodexAuthorizationGate(service);
        assertThatThrownBy(gate::requireAuthorized).hasMessage("CODEX_AUTH_REQUIRED");
        assertThat(session.refreshed).isTrue();
    }

    @Test void logout_between_check_and_start_rejects_turn() {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var lease = gate.requireAuthorized();
        service.logout();
        var started = new AtomicBoolean();
        assertThatThrownBy(() -> gate.dispatch(lease, () -> started.set(true))).hasMessage("CODEX_AUTH_REQUIRED");
        assertThat(started).isFalse();
    }

    @Test void logout_cancels_running_turn() {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var cancelled = new AtomicBoolean();
        gate.registerCancellation(gate.requireAuthorized(), () -> cancelled.set(true));
        service.logout(() -> { });
        assertThat(cancelled).isTrue();
        assertThat(service.currentState().authState().name()).isEqualTo("SIGNED_OUT");
    }

    @Test void cancellation_attached_after_logout_closes_raced_startup() {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var lease = gate.requireAuthorized();
        service.logout();
        var cancelled = new AtomicBoolean();
        assertThatThrownBy(() -> gate.registerCancellation(lease, () -> cancelled.set(true)))
                .hasMessage("CODEX_AUTH_REQUIRED");
        assertThat(cancelled).isTrue();
    }

    @Test void logout_cleanup_failure_keeps_gate_blocked() {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var fail = new AtomicBoolean(true);
        gate.registerCancellation(gate.requireAuthorized(), () -> {
            if (fail.get()) throw new IllegalStateException("provider details");
        });
        service.logout();
        assertThatThrownBy(gate::requireAuthorized).hasMessage("CODEX_LOGOUT_REQUIRED");
        fail.set(false);
        service.logout();
        assertThat(service.currentState().authState().name()).isEqualTo("SIGNED_OUT");
    }

    @Test void signed_out_discovery_is_read_only_and_logout_closes_it() {
        var gate = new ForgeCodexAuthorizationGate(service);
        var lease = gate.observe();
        var closed = new AtomicBoolean();
        gate.registerCancellation(lease, () -> closed.set(true));
        assertThatThrownBy(() -> gate.dispatch(lease, () -> { })).hasMessage("CODEX_AUTH_REQUIRED");
        service.logout();
        assertThat(closed).isTrue();
    }

    @Test void late_cleanup_failure_blocks_new_work_and_is_retried_by_logout() {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var lease = gate.requireAuthorized();
        service.logout();
        var failed = new AtomicBoolean(true);
        var cleaned = new AtomicBoolean();
        assertThatThrownBy(() -> gate.registerCancellation(lease, () -> {
            if (failed.get()) throw new IllegalStateException("private provider details");
            cleaned.set(true);
        })).hasMessage("CODEX_AUTH_CLEANUP_FAILED");
        assertThatThrownBy(gate::requireAuthorized).hasMessage("CODEX_LOGOUT_REQUIRED");
        failed.set(false);
        service.logout();
        assertThat(cleaned).isTrue();
        assertThat(service.currentState().authState().name()).isEqualTo("SIGNED_OUT");
    }

    @Test void release_cannot_discard_failed_cleanup_obligation() {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var lease = gate.requireAuthorized();
        var failed = new AtomicBoolean(true);
        var cleaned = new AtomicBoolean();
        gate.registerCancellation(lease, () -> {
            if (failed.get()) throw new IllegalStateException("private provider details");
            cleaned.set(true);
        });
        service.logout();
        assertThatThrownBy(() -> gate.release(lease)).hasMessage("CODEX_AUTH_CLEANUP_FAILED");
        failed.set(false);
        service.logout();
        assertThat(cleaned).isTrue();
    }

    @Test void already_admitted_write_does_not_hold_state_lock_and_is_owned_by_cleanup() throws Exception {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var lease = gate.requireAuthorized();
        var closed = new AtomicBoolean();
        gate.registerCancellation(lease, () -> closed.set(true));
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        var write = java.util.concurrent.CompletableFuture.runAsync(() -> gate.dispatch(lease, () -> {
            admitted.countDown();
            try { assertThat(proceed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            assertThat(closed).isTrue();
        }));
        assertThat(admitted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        service.logout();
        proceed.countDown();
        write.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertThatThrownBy(() -> gate.dispatch(lease, () -> { })).hasMessage("CODEX_AUTH_REQUIRED");
    }

    @Test void cleanup_attached_during_cancellation_is_retained_when_it_fails() {
        session.connected = true;
        var gate = new ForgeCodexAuthorizationGate(service);
        var lease = gate.requireAuthorized();
        var first = new AtomicBoolean(true);
        var failed = new AtomicBoolean(true);
        var cleaned = new AtomicBoolean();
        gate.registerCancellation(lease, () -> {
            if (first.getAndSet(false)) {
                assertThatThrownBy(() -> gate.registerCancellation(lease, () -> {
                    if (failed.get()) throw new IllegalStateException("cleanup failure");
                    cleaned.set(true);
                })).hasMessage("CODEX_AUTH_CLEANUP_FAILED");
            }
        });
        service.logout();
        failed.set(false);
        service.logout();
        assertThat(cleaned).isTrue();
    }

    private static class AccountSession implements LlmAuthorizationGateway.Session {
        boolean connected;
        boolean refreshed;
        public LlmAuthorizationGateway.Account readAccount(boolean refresh) {
            refreshed |= refresh;
            return new LlmAuthorizationGateway.Account(connected, connected ? "forge@example.test" : null, null);
        }
        public LlmAuthorizationGateway.Login startLogin() { throw new UnsupportedOperationException(); }
        public void cancelLogin(String id) { }
        public void logout() { connected = false; }
        public List<LlmAuthorizationGateway.Event> drainEvents() { return List.of(); }
        public boolean healthy() { return true; }
        public void close() { }
    }
}
