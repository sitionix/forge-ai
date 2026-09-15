package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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

    @Test
    void abortRealWrapperTreeIsBoundedAndUnblocksStdinWriteAndClose() throws Exception {
        final Process process = new ProcessBuilder("/bin/sh", "-c", "sleep 30 <&0 & wait").start();
        final ProcessHandle child = awaitChild(process, Duration.ofSeconds(1));
        final CodexRecoveryLifecycle lifecycle = new CodexRecoveryLifecycle();
        assertThat(lifecycle.register(process)).isTrue();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CompletableFuture<Void> write = runAsync(() -> {
            writeEntered.countDown();
            process.getOutputStream().write(new byte[8 * 1024 * 1024]);
            process.getOutputStream().flush();
        });
        CompletableFuture<Void> close = null;
        CompletableFuture<Void> abort = null;

        try {
            assertThat(writeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(completesWithin(write, Duration.ofMillis(100))).isFalse();
            close = runAsync(() -> process.getOutputStream().close());
            assertThat(completesWithin(close, Duration.ofMillis(100))).isFalse();

            abort = runAsync(lifecycle::abort);

            abort.get(400, TimeUnit.MILLISECONDS);
            awaitCompletion(write, Duration.ofSeconds(1));
            awaitCompletion(close, Duration.ofSeconds(1));
            assertThat(awaitExit(process.toHandle(), Duration.ofSeconds(1))).isTrue();
            assertThat(awaitExit(child, Duration.ofSeconds(1))).isTrue();
            lifecycle.abort();
        } finally {
            child.destroyForcibly();
            process.toHandle().destroyForcibly();
            awaitCompletionIfPresent(abort, Duration.ofSeconds(1));
            awaitCompletionIfPresent(write, Duration.ofSeconds(1));
            awaitCompletionIfPresent(close, Duration.ofSeconds(1));
        }
    }

    private static ProcessHandle awaitChild(final Process process, final Duration timeout)
            throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final var children = process.descendants().toList();
            if (!children.isEmpty()) {
                return children.getFirst();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("wrapper process did not expose its child");
    }

    private static boolean awaitExit(final ProcessHandle process, final Duration timeout)
            throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return !process.isAlive();
    }

    private static CompletableFuture<Void> runAsync(final ThrowingRunnable action) {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                action.run();
                result.complete(null);
            } catch (final Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static boolean completesWithin(final CompletableFuture<Void> task, final Duration timeout)
            throws InterruptedException {
        try {
            task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (final java.util.concurrent.TimeoutException expected) {
            return false;
        } catch (final ExecutionException completedWithFailure) {
            return true;
        }
    }

    private static void awaitCompletion(final CompletableFuture<Void> task, final Duration timeout)
            throws InterruptedException, java.util.concurrent.TimeoutException {
        try {
            task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final ExecutionException expectedAfterPipeTermination) {
            // Pipe writes and close may complete with IOException when the owned process tree is terminated.
        }
    }

    private static void awaitCompletionIfPresent(final CompletableFuture<Void> task, final Duration timeout)
            throws InterruptedException {
        if (task == null) {
            return;
        }
        try {
            awaitCompletion(task, timeout);
        } catch (final java.util.concurrent.TimeoutException ignored) {
            // The assertions report the bounded-cleanup failure; the real handles above have still been terminated.
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
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
