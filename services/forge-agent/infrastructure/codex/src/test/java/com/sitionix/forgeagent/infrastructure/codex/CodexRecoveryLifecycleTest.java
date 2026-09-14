package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CodexRecoveryLifecycleTest {

    @Test
    void abortBeforeLateRegistrationForceKillsExactlyOnce() throws Exception {
        final CodexRecoveryLifecycle lifecycle = new CodexRecoveryLifecycle();
        final FakeCodexProcess process = new FakeCodexProcess();

        lifecycle.abort();
        assertThat(lifecycle.register(process)).isFalse();
        lifecycle.abort();

        assertThat(process.awaitExit(Duration.ofSeconds(1))).isTrue();
        assertThat(process.forceCalls()).isEqualTo(1);
    }

    @Test
    void concurrentRegisterAndAbortAlwaysForceKillExactlyOnce() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            final CodexRecoveryLifecycle lifecycle = new CodexRecoveryLifecycle();
            final FakeCodexProcess process = new FakeCodexProcess();
            final CountDownLatch start = new CountDownLatch(1);
            final Thread register = Thread.ofVirtual().start(() -> {
                await(start);
                lifecycle.register(process);
            });
            final Thread abort = Thread.ofVirtual().start(() -> {
                await(start);
                lifecycle.abort();
            });

            start.countDown();
            register.join();
            abort.join();

            assertThat(process.awaitExit(Duration.ofSeconds(1))).as("iteration %s", iteration).isTrue();
            assertThat(process.forceCalls()).as("iteration %s", iteration).isEqualTo(1);
            lifecycle.abort();
            assertThat(process.forceCalls()).as("idempotent iteration %s", iteration).isEqualTo(1);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
