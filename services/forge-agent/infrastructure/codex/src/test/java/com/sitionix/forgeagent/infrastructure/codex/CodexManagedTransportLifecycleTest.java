package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real local pipes; callback models acknowledgement only, never actual UID/systemd isolation. */
@Timeout(10)
class CodexManagedTransportLifecycleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void normalCloseRequiresOwnedStopAcknowledgement() throws Exception {
        try (var fixture = new Fixture("/bin/sleep", "30")) {
            fixture.transport.close();
            assertThat(fixture.stops.get()).isEqualTo(1);
            assertThat(fixture.transport.cleanupComplete()).isTrue();
        }
    }

    @Test void alreadyExitedPipeStillRequiresOwnedStop() throws Exception {
        Process pipe = new ProcessBuilder("/bin/true").start();
        pipe.waitFor();
        try (var fixture = new Fixture(pipe)) {
            fixture.transport.close();
            assertThat(fixture.stops.get()).isEqualTo(1);
            assertThat(fixture.transport.cleanupComplete()).isTrue();
        }
    }

    @Test void stdoutEofStopsOwnedUnitWhilePipeWasStillAlive() throws Exception {
        try (var fixture = new Fixture("/bin/sh", "-c", "exec 1>&-; exec sleep 30")) {
            awaitClean(fixture.transport);
            assertThat(fixture.stops.get()).isEqualTo(1);
            assertThat(fixture.pipe.isAlive()).isFalse();
        }
    }

    @Test void responseTimeoutAfterDispatchRequiresOwnedStop() throws Exception {
        try (var fixture = new Fixture("/bin/sleep", "30")) {
            var dispatched = new CountDownLatch(1);
            assertThatThrownBy(() -> fixture.transport.request("read", mapper.createObjectNode(), Duration.ofMillis(150),
                    write -> { write.run(); dispatched.countDown(); }))
                    .isInstanceOf(CodexTransportException.class).hasMessageContaining("timed out");
            assertThat(dispatched.getCount()).isZero();
            assertThat(fixture.stops.get()).isEqualTo(1);
            assertThat(fixture.transport.cleanupComplete()).isTrue();
        }
    }

    @Test void closeStopsOwnedUnitBeforeAcquiringBlockedStdinMonitor() throws Exception {
        try (var fixture = new Fixture("/bin/sleep", "30")) {
            var entered = new CountDownLatch(1);
            var request = new CompletableFuture<Void>();
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    fixture.transport.request("blocked", mapper.createObjectNode().put("body", "x".repeat(8*1024*1024)),
                            Duration.ofSeconds(8), write -> { entered.countDown(); write.run(); });
                    request.complete(null);
                } catch (Throwable failure) { request.completeExceptionally(failure); }
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> request.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            var close = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try { fixture.transport.close(); close.complete(null); }
                catch (Throwable failure) { close.completeExceptionally(failure); }
            });
            close.get(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> request.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
            writer.join(1000);
            assertThat(writer.isAlive()).isFalse();
            assertThat(fixture.stops.get()).isEqualTo(1);
            assertThat(fixture.transport.cleanupComplete()).isTrue();
        }
    }

    @Test void failedOwnedStopIsNotCompletionAndRetriesAfterPipeExit() throws Exception {
        Process pipe = new ProcessBuilder("/bin/sleep", "30").start();
        AtomicInteger attempts = new AtomicInteger();
        var managed = new ManagedRuntimeProcess(pipe, () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("synthetic unconfirmed stop");
            pipe.toHandle().destroyForcibly();
        });
        var transport = transport(managed);
        try {
            assertThatThrownBy(transport::close).isInstanceOf(CodexTransportException.class)
                    .hasMessageContaining("owned runtime cleanup");
            assertThat(transport.cleanupComplete()).isFalse();
            assertThat(attempts.get()).isEqualTo(1);
            pipe.toHandle().destroyForcibly(); pipe.waitFor();
            transport.close();
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(transport.cleanupComplete()).isTrue();
        } finally { pipe.toHandle().destroyForcibly(); pipe.waitFor(); }
    }

    private CodexJsonRpcTransport transport(Process process) {
        var properties = new CodexAppServerProperties();
        properties.setGracefulTerminateTimeout(Duration.ofMillis(100));
        properties.setForceKillTimeout(Duration.ofSeconds(1));
        return new CodexJsonRpcTransport(mapper, new StartedCodexAppServer(process,List.of("fixture"),Instant.now()),properties);
    }

    private static void awaitClean(CodexJsonRpcTransport transport) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (!transport.cleanupComplete() && System.nanoTime()<deadline) Thread.sleep(5);
        assertThat(transport.cleanupComplete()).isTrue();
    }

    private final class Fixture implements AutoCloseable {
        final Process pipe;
        final AtomicInteger stops=new AtomicInteger();
        final CodexJsonRpcTransport transport;
        Fixture(String... command) throws Exception { this(new ProcessBuilder(command).start()); }
        Fixture(Process pipe) {
            this.pipe=pipe;
            transport=transport(new ManagedRuntimeProcess(pipe, () -> { stops.incrementAndGet(); pipe.toHandle().destroyForcibly(); }));
        }
        @Override public void close() throws Exception {
            pipe.toHandle().destroyForcibly(); pipe.waitFor();
            transport.close();
        }
    }
}
