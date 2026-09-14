package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspection;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexRecoveryInspectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionWorkspace workspace = new ExecutionWorkspace(Path.of("/workspace"), List.of(Path.of("/workspace")));

    @Test
    void supportsOnlyExactAuditedCodexVersion() {
        final RecordingStarter starter = new RecordingStarter();
        final CodexRecoveryInspector inspector = this.inspector(starter);

        assertThat(inspector.supports("codex", "0.154.0")).isTrue();
        assertThat(inspector.supports("CODEX", "0.154.0")).isFalse();
        assertThat(inspector.supports("codex", "0.153.2")).isFalse();
        assertThat(inspector.supports(null, "0.154.0")).isFalse();
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
                () -> this.inspector(starter).inspect(this.inspection("0.154.0")));

        final FakeCodexProcess process = starter.awaitProcess();
        final JsonNode initialize = this.readRequest(process);
        assertThat(initialize.path("method").asText()).isEqualTo("initialize");
        this.reply(process, initialize, "{\"userAgent\":\"codex/0.154.0\"}");
        final JsonNode initialized = this.readRequest(process);
        assertThat(initialized.path("method").asText()).isEqualTo("initialized");
        final JsonNode turns = this.readRequest(process);
        assertThat(turns.path("method").asText()).isEqualTo("thread/turns/list");
        this.reply(process, turns,
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");

        assertThat(recovered.get(1, TimeUnit.SECONDS)).isEqualTo(ProviderTurnRecoveryResult.terminal(
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Codex turn status completed"));
        assertThat(process.destroyed()).isTrue();
    }

    @Test
    void mismatchedLiveVersionReturnsUnknownAndClosesWithoutInspectionRequest() throws Exception {
        final RecordingStarter starter = new RecordingStarter();
        final CompletableFuture<ProviderTurnRecoveryResult> recovered = CompletableFuture.supplyAsync(
                () -> this.inspector(starter).inspect(this.inspection("0.154.0")));

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
                "codex", "0.154.0", " ", "turn-target", this.workspace);

        assertThat(this.inspector(starter).inspect(inspection).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(starter.processes()).isEmpty();
    }

    @Test
    void transportConstructionFailureStillCleansStartedProcess() {
        final FakeCodexProcess delegate = new FakeCodexProcess();
        final OutputStreamFailingProcess process = new OutputStreamFailingProcess(delegate);
        try {
            final ProviderTurnRecoveryResult result = this.inspector(new FixedStarter(process))
                    .inspect(this.inspection("0.154.0"));

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
                    () -> this.inspector(new FixedStarter(process)).inspect(this.inspection("0.154.0")));
            final JsonNode initialize = this.readRequest(process);
            this.reply(process, initialize, "{\"userAgent\":\"codex/0.154.0\"}");
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

    private CodexRecoveryInspector inspector(final RecordingStarter starter) {
        return this.inspector((CodexAppServerProcessStarter) starter);
    }

    private CodexRecoveryInspector inspector(final CodexAppServerProcessStarter starter) {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setRequestTimeout(Duration.ofSeconds(1));
        properties.setGracefulTerminateTimeout(Duration.ofMillis(20));
        properties.setForceKillTimeout(Duration.ofMillis(20));
        return new CodexRecoveryInspector(this.objectMapper, starter, properties,
                new CodexRecoveryProtocol(this.objectMapper));
    }

    private AgentExecutionRecoveryInspection inspection(final String version) {
        return new AgentExecutionRecoveryInspection(
                "codex", version, "thread-target", "turn-target", this.workspace);
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
