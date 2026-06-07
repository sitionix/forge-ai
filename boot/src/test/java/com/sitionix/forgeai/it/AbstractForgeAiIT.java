package com.sitionix.forgeai.it;

import java.time.Duration;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.fail;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
abstract class AbstractForgeAiIT {

    protected void eventually(final Duration timeout, final Runnable assertion) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError lastAssertionError = null;
        RuntimeException lastRuntimeException = null;

        while (System.nanoTime() < deadline) {
            try {
                assertion.run();
                return;
            } catch (final AssertionError assertionError) {
                lastAssertionError = assertionError;
            } catch (final RuntimeException runtimeException) {
                lastRuntimeException = runtimeException;
            }
            this.sleepBriefly();
        }

        if (lastAssertionError != null) {
            throw lastAssertionError;
        }
        if (lastRuntimeException != null) {
            throw lastRuntimeException;
        }
        fail("Condition was not satisfied within %s", timeout);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100L);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for asynchronous test assertion");
        }
    }
}
