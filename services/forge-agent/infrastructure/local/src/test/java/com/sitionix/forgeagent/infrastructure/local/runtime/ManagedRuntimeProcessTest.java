package com.sitionix.forgeagent.infrastructure.local.runtime;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManagedRuntimeProcessTest {
    @Test void cleanupIsOwnedAndIdempotentAfterPipeExit() throws Exception {
        Process pipe = new ProcessBuilder("/bin/true").start();
        pipe.waitFor();
        AtomicInteger stops = new AtomicInteger();
        var managed = new ManagedRuntimeProcess(pipe, stops::incrementAndGet);
        managed.terminateOwnedUnit();
        managed.terminateOwnedUnit();
        assertThat(stops.get()).isEqualTo(1);
        assertThat(managed.exitValue()).isZero();
    }
    @Test void failedCleanupRemainsRetryable() throws Exception {
        Process pipe = new ProcessBuilder("/bin/true").start();
        AtomicInteger attempts = new AtomicInteger();
        var managed = new ManagedRuntimeProcess(pipe, () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("unconfirmed");
        });
        assertThatThrownBy(managed::terminateOwnedUnit).hasMessage("unconfirmed");
        managed.terminateOwnedUnit();
        assertThat(attempts.get()).isEqualTo(2);
        pipe.waitFor();
    }
}
