package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspection;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodexRecoveryInspectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionWorkspace workspace = new ExecutionWorkspace(Path.of("/workspace"), List.of(Path.of("/workspace")));

    @Test
    void supportsOnlyExactAuditedCodexVersion() {
        final RecordingStarter starter = new RecordingStarter();
        final CodexRecoveryInspector inspector = this.inspector(starter);

        assertThat(inspector.supports("codex", "0.157.0")).isTrue();
        assertThat(inspector.supports("CODEX", "0.157.0")).isFalse();
        assertThat(inspector.supports("codex", "0.153.2")).isFalse();
        assertThat(inspector.supports(null, "0.157.0")).isFalse();
        assertThat(inspector.supports("codex", null)).isFalse();
        assertThat(starter.processes()).isEmpty();
    }

    @Test
    void unsupportedPersistedVersionReturnsUnknownWithoutStartingProcess() {
        final RecordingStarter starter = new RecordingStarter();

        final ProviderTurnRecoveryResult result = this.inspector(starter).inspect(this.inspection("0.153.2"));

        assertThat(result.state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(starter.processes()).isEmpty();
    }

    @Test
    void freshProcessInitializesThenInspectsExactTurnAndAlwaysCloses() throws Exception {
        final RecordingStarter starter = new RecordingStarter();
        final CompletableFuture<ProviderTurnRecoveryResult> recovered = CompletableFuture.supplyAsync(
                () -> this.inspector(starter).inspect(this.inspection("0.157.0")));

        final FakeCodexProcess process = starter.awaitProcess();
        final JsonNode initialize = this.readRequest(process);
        assertThat(initialize.path("method").asText()).isEqualTo("initialize");
        this.reply(process, initialize, "{\"userAgent\":\"codex/0.157.0\"}");
        final JsonNode initialized = this.readRequest(process);
        assertThat(initialized.path("method").asText()).isEqualTo("initialized");
        final JsonNode turns = this.readRequest(process);
        assertThat(turns.path("method").asText()).isEqualTo("thread/turns/list");
        this.reply(process, turns,
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");

        assertThat(recovered.get(1, TimeUnit.SECONDS)).isEqualTo(ProviderTurnRecoveryResult.terminal(
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Codex turn status completed"));
        assertThat(process.destroyed()).isTrue();
        assertThat(process.forciblyDestroyed()).isFalse();
    }

    @Test
    void mismatchedLiveVersionReturnsUnknownAndClosesWithoutInspectionRequest() throws Exception {
        final RecordingStarter starter = new RecordingStarter();
        final CompletableFuture<ProviderTurnRecoveryResult> recovered = CompletableFuture.supplyAsync(
                () -> this.inspector(starter).inspect(this.inspection("0.157.0")));

        final FakeCodexProcess process = starter.awaitProcess();
        final JsonNode initialize = this.readRequest(process);
        this.reply(process, initialize, "{\"userAgent\":\"codex/0.155.0\"}");
        final JsonNode initialized = this.readRequest(process);
        assertThat(initialized.path("method").asText()).isEqualTo("initialized");

        assertThat(recovered.get(1, TimeUnit.SECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(process.pendingClientRequestBytes()).isZero();
        assertThat(process.destroyed()).isTrue();
    }

    @Test
    void malformedIdentityReturnsUnknownWithoutStartingProcess() {
        final RecordingStarter starter = new RecordingStarter();
        final AgentExecutionRecoveryInspection inspection = new AgentExecutionRecoveryInspection(
                "codex", "0.157.0", " ", "turn-target", this.workspace, Instant.now().plusSeconds(5));

        assertThat(this.inspector(starter).inspect(inspection).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(starter.processes()).isEmpty();
    }

    @Test
    void transportConstructionFailureStillCleansStartedProcess() {
        final FakeCodexProcess delegate = new FakeCodexProcess();
        final OutputStreamFailingProcess process = new OutputStreamFailingProcess(delegate);
        try {
            final ProviderTurnRecoveryResult result = this.inspector(new FixedStarter(process))
                    .inspect(this.inspection("0.157.0"));

            assertThat(result.state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
            assertThat(delegate.destroyed()).isTrue();
            assertThat(delegate.isAlive()).isFalse();
        } finally {
            delegate.terminateNow();
        }
    }

    @Test
    void cleanupFailureOverridesTerminalClassificationWithUnknown() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, false);
        try {
            final CompletableFuture<ProviderTurnRecoveryResult> recovered = CompletableFuture.supplyAsync(
                    () -> this.inspector(new FixedStarter(process)).inspect(this.inspection("0.157.0")));
            final JsonNode initialize = this.readRequest(process);
            this.reply(process, initialize, "{\"userAgent\":\"codex/0.157.0\"}");
            assertThat(this.readRequest(process).path("method").asText()).isEqualTo("initialized");
            final JsonNode turns = this.readRequest(process);
            this.reply(process, turns,
                    "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");

            assertThat(recovered.get(1, TimeUnit.SECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
            assertThat(process.destroyed()).isTrue();
            assertThat(process.forciblyDestroyed()).isTrue();
        } finally {
            process.terminateNow();
        }
    }

    @Test
    void initializeTimeoutUsesRemainingDeadlineAndTerminatesProcess() throws Exception {
        final RecordingStarter starter = new RecordingStarter();
        final long startedAt = System.nanoTime();

        final ProviderTurnRecoveryResult result = this.inspector(starter).inspect(new AgentExecutionRecoveryInspection(
                "codex", "0.157.0", "thread-target", "turn-target", this.workspace,
                Instant.now().plusMillis(90)));

        assertThat(result.state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(500));
        assertThat(starter.processes()).singleElement().satisfies(process -> {
            assertThat(process.forciblyDestroyed()).isTrue();
            assertThat(process.isAlive()).isFalse();
        });
    }

    @Test
    void blockedStarterReturnsUnknownThenForceKillsProcessReturnedAfterDeadline() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess();
        final BlockingStarter starter = new BlockingStarter(process);
        final CompletableFuture<ProviderTurnRecoveryResult> recovered = CompletableFuture.supplyAsync(
                () -> this.inspector(starter).inspect(this.inspection(Instant.now().plusMillis(120))));

        try {
            assertThat(starter.entered().await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(recovered.get(400, TimeUnit.MILLISECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
            assertThat(process.isAlive()).isTrue();

            starter.release().countDown();
            assertThat(starter.returned().await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(process.awaitExit(Duration.ofSeconds(1))).isTrue();
            assertThat(process.forciblyDestroyed()).isTrue();
            assertThat(process.pendingClientRequestBytes()).isZero();
        } finally {
            starter.release().countDown();
            process.terminateNow();
            recovered.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockedWriteIsAbortedAndProcessTerminatesWithinLifecycleDeadline() throws Exception {
        final BlockingIoProcess process = new BlockingIoProcess(BlockingOperation.WRITE);
        final CompletableFuture<ProviderTurnRecoveryResult> recovered = CompletableFuture.supplyAsync(
                () -> this.inspector(new FixedStarter(process))
                        .inspect(this.inspection(Instant.now().plusMillis(160))));

        try {
            assertThat(process.ioEntered().await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(recovered.get(500, TimeUnit.MILLISECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
            assertThat(process.awaitExit(Duration.ofSeconds(1))).isTrue();
            assertThat(process.forceCalls()).isEqualTo(1);
        } finally {
            process.releaseIo();
            process.terminateNow();
            recovered.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockedCloseIsForceKilledAndCannotReturnTerminalAfterDeadline() throws Exception {
        final BlockingIoProcess process = new BlockingIoProcess(BlockingOperation.CLOSE);
        final CompletableFuture<ProviderTurnRecoveryResult> recovered = CompletableFuture.supplyAsync(
                () -> this.inspector(new FixedStarter(process))
                        .inspect(this.inspection(Instant.now().plusMillis(260))));

        try {
            final JsonNode initialize = this.readRequest(process.delegate());
            this.reply(process.delegate(), initialize, "{\"userAgent\":\"codex/0.157.0\"}");
            assertThat(this.readRequest(process.delegate()).path("method").asText()).isEqualTo("initialized");
            final JsonNode turns = this.readRequest(process.delegate());
            this.reply(process.delegate(), turns,
                    "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");
            assertThat(process.ioEntered().await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(recovered.get(500, TimeUnit.MILLISECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
            assertThat(process.awaitExit(Duration.ofSeconds(1))).isTrue();
            assertThat(process.forceCalls()).isEqualTo(1);
        } finally {
            process.releaseIo();
            process.terminateNow();
            recovered.get(1, TimeUnit.SECONDS);
        }
    }

    private CodexRecoveryInspector inspector(final RecordingStarter starter) {
        return this.inspector((CodexAppServerProcessStarter) starter);
    }

    private CodexRecoveryInspector inspector(final CodexAppServerProcessStarter starter) {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setRequestTimeout(Duration.ofSeconds(1));
        properties.setGracefulTerminateTimeout(Duration.ofMillis(20));
        properties.setForceKillTimeout(Duration.ofMillis(20));
        final Clock clock = Clock.systemUTC();
        return new CodexRecoveryInspector(this.objectMapper, starter, properties,
                new CodexRecoveryProtocol(this.objectMapper, clock), clock);
    }

    private AgentExecutionRecoveryInspection inspection(final String version) {
        return this.inspection(version, Instant.now().plusSeconds(5));
    }

    private AgentExecutionRecoveryInspection inspection(final Instant deadline) {
        return this.inspection("0.157.0", deadline);
    }

    private AgentExecutionRecoveryInspection inspection(final String version, final Instant deadline) {
        return new AgentExecutionRecoveryInspection(
                "codex", version, "thread-target", "turn-target", this.workspace, deadline);
    }

    private JsonNode readRequest(final FakeCodexProcess process) throws Exception {
        return this.objectMapper.readTree(process.readRequest());
    }

    private void reply(final FakeCodexProcess process, final JsonNode request, final String result) {
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":" + result + "}");
    }

    private static final class RecordingStarter implements CodexAppServerProcessStarter {
        private final List<FakeCodexProcess> processes = new ArrayList<>();

        @Override
        public synchronized StartedCodexAppServer start(final Path workingDirectory) {
            final FakeCodexProcess process = new FakeCodexProcess();
            this.processes.add(process);
            this.notifyAll();
            return new StartedCodexAppServer(process, List.of("codex", "app-server", "--stdio"), Instant.now());
        }

        synchronized FakeCodexProcess awaitProcess() throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (this.processes.isEmpty() && System.nanoTime() < deadline) {
                this.wait(10);
            }
            assertThat(this.processes).isNotEmpty();
            return this.processes.getFirst();
        }

        synchronized List<FakeCodexProcess> processes() {
            return List.copyOf(this.processes);
        }
    }

    private record FixedStarter(Process process) implements CodexAppServerProcessStarter {
        @Override
        public StartedCodexAppServer start(final Path workingDirectory) {
            return new StartedCodexAppServer(
                    this.process, List.of("codex", "app-server", "--stdio"), Instant.now());
        }
    }

    private record BlockingStarter(FakeCodexProcess process, CountDownLatch entered, CountDownLatch release,
                                   CountDownLatch returned) implements CodexAppServerProcessStarter {
        private BlockingStarter(final FakeCodexProcess process) {
            this(process, new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        }

        @Override
        public StartedCodexAppServer start(final Path workingDirectory) {
            this.entered.countDown();
            awaitUninterruptibly(this.release);
            this.returned.countDown();
            return new StartedCodexAppServer(
                    this.process, List.of("codex", "app-server", "--stdio"), Instant.now());
        }
    }

    private enum BlockingOperation { WRITE, CLOSE }

    private static final class BlockingIoProcess extends Process {
        private final FakeCodexProcess delegate = new FakeCodexProcess();
        private final BlockingOperation operation;
        private final CountDownLatch ioEntered = new CountDownLatch(1);
        private final CountDownLatch ioRelease = new CountDownLatch(1);
        private final AtomicInteger forceCalls = new AtomicInteger();
        private final OutputStream output = new OutputStream() {
            @Override
            public void write(final int value) throws IOException {
                if (BlockingIoProcess.this.operation == BlockingOperation.WRITE) {
                    BlockingIoProcess.this.blockIo();
                }
                BlockingIoProcess.this.delegate.getOutputStream().write(value);
            }

            @Override
            public void write(final byte[] values, final int offset, final int length) throws IOException {
                if (BlockingIoProcess.this.operation == BlockingOperation.WRITE) {
                    BlockingIoProcess.this.blockIo();
                }
                BlockingIoProcess.this.delegate.getOutputStream().write(values, offset, length);
            }

            @Override
            public void flush() throws IOException {
                BlockingIoProcess.this.delegate.getOutputStream().flush();
            }

            @Override
            public void close() throws IOException {
                if (BlockingIoProcess.this.operation == BlockingOperation.CLOSE) {
                    BlockingIoProcess.this.blockIo();
                }
                BlockingIoProcess.this.delegate.getOutputStream().close();
            }
        };

        private BlockingIoProcess(final BlockingOperation operation) {
            this.operation = operation;
        }

        private void blockIo() {
            this.ioEntered.countDown();
            awaitUninterruptibly(this.ioRelease);
        }

        CountDownLatch ioEntered() { return this.ioEntered; }
        int forceCalls() { return this.forceCalls.get(); }
        FakeCodexProcess delegate() { return this.delegate; }
        void releaseIo() { this.ioRelease.countDown(); }
        void terminateNow() { this.delegate.terminateNow(); }
        boolean awaitExit(final Duration timeout) throws InterruptedException { return this.delegate.awaitExit(timeout); }

        @Override public OutputStream getOutputStream() { return this.output; }
        @Override public InputStream getInputStream() { return this.delegate.getInputStream(); }
        @Override public InputStream getErrorStream() { return this.delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return this.delegate.waitFor(); }
        @Override public boolean waitFor(final long timeout, final TimeUnit unit) throws InterruptedException {
            return this.delegate.waitFor(timeout, unit);
        }
        @Override public int exitValue() { return this.delegate.exitValue(); }
        @Override public void destroy() { this.delegate.destroy(); }
        @Override public Process destroyForcibly() {
            this.forceCalls.incrementAndGet();
            this.ioRelease.countDown();
            this.delegate.destroyForcibly();
            return this;
        }
        @Override public boolean isAlive() { return this.delegate.isAlive(); }
        @Override public long pid() { return this.delegate.pid(); }
    }

    private static void awaitUninterruptibly(final CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class OutputStreamFailingProcess extends Process {
        private final FakeCodexProcess delegate;

        private OutputStreamFailingProcess(final FakeCodexProcess delegate) {
            this.delegate = delegate;
        }

        @Override public OutputStream getOutputStream() { throw new IllegalStateException("output stream unavailable"); }
        @Override public InputStream getInputStream() { return this.delegate.getInputStream(); }
        @Override public InputStream getErrorStream() { return this.delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return this.delegate.waitFor(); }
        @Override public boolean waitFor(final long timeout, final TimeUnit unit) throws InterruptedException {
            return this.delegate.waitFor(timeout, unit);
        }
        @Override public int exitValue() { return this.delegate.exitValue(); }
        @Override public void destroy() { this.delegate.destroy(); }
        @Override public Process destroyForcibly() { this.delegate.destroyForcibly(); return this; }
        @Override public boolean isAlive() { return this.delegate.isAlive(); }
        @Override public long pid() { return this.delegate.pid(); }
    }
}
