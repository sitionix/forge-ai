package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class CodexManagedRecoveryLifecycleTest {
    @Test void failedAbortCanRetryAndConfirmedAbortIsIdempotent() throws Exception {
        Process pipe=new ProcessBuilder("/bin/true").start(); pipe.waitFor();
        AtomicInteger attempts=new AtomicInteger();
        var managed=new ManagedRuntimeProcess(pipe, () -> {
            if (attempts.incrementAndGet()==1) throw new IllegalStateException("synthetic unconfirmed");
        });
        var lifecycle=new CodexRecoveryLifecycle(); lifecycle.register(managed);
        assertThatThrownBy(lifecycle::abort).hasMessage("synthetic unconfirmed");
        lifecycle.abort(); lifecycle.abort();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test void concurrentCancellationWaitsForFailedAttemptAndRetriesSerially() throws Exception {
        Process pipe=new ProcessBuilder("/bin/true").start(); pipe.waitFor();
        AtomicInteger attempts=new AtomicInteger();
        var firstEntered=new CountDownLatch(1); var releaseFirst=new CountDownLatch(1);
        var managed=new ManagedRuntimeProcess(pipe, () -> {
            if (attempts.incrementAndGet()==1) {
                firstEntered.countDown();
                try { if (!releaseFirst.await(3,TimeUnit.SECONDS)) throw new AssertionError("release missing"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                throw new IllegalStateException("synthetic first failure");
            }
        });
        var lifecycle=new CodexRecoveryLifecycle(); lifecycle.register(managed);
        var first=run(lifecycle::abort);
        assertThat(firstEntered.await(1,TimeUnit.SECONDS)).isTrue();
        var second=run(lifecycle::abort);
        try {
            assertThatThrownBy(() -> second.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally { releaseFirst.countDown(); }
        assertThatThrownBy(() -> first.get(1,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        second.get(1,TimeUnit.SECONDS);
        lifecycle.abort();
        assertThat(attempts.get()).isEqualTo(2);
    }

    private static CompletableFuture<Void> run(Runnable action) {
        var result=new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            try { action.run(); result.complete(null); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }
}
