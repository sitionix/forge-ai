package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspection;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "forge.codex.live-recovery-e2e", matches = "true")
class CodexRecoveryE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void freshProcessInspectsExactTerminalTurnWithoutStartingOrResumingExecution() throws Exception {
        final Path workspacePath = Files.createTempDirectory("forge-codex-live-recovery-");
        final ExecutionWorkspace workspace = new ExecutionWorkspace(workspacePath, List.of(workspacePath));
        final String model = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        final JsonNode schema = this.objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string"}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """);
        final AtomicReference<String> threadId = new AtomicReference<>();
        final AtomicReference<String> turnId = new AtomicReference<>();
        final AtomicReference<String> version = new AtomicReference<>();
        final CodexAppServerProperties executionProperties = this.properties(workspacePath);
        final CodexAppServerClient executionClient = this.client(executionProperties);
        try {
            executionClient.executeDurable(new CodexTurnRequest(
                    "Return JSON with answer set to recovery-ready.",
                    "Return only JSON matching the supplied schema.", model, null, schema, workspace
            ), null, new CodexExecutionIdentityCallbacks() {
                @Override
                public void conversationStarted(final String providerConversationId, final String providerVersion) {
                    threadId.set(providerConversationId);
                    version.set(providerVersion);
                }

                @Override
                public void turnStarted(final String providerTurnId) {
                    turnId.set(providerTurnId);
                }
            });
        } finally {
            executionClient.close();
        }

        assertThat(version.get()).isEqualTo("0.154.0");
        assertThat(threadId.get()).isNotBlank();
        assertThat(turnId.get()).isNotBlank();

        final CodexAppServerProperties recoveryProperties = this.properties(workspacePath);
        final RecordingProcessStarter recoveryStarter = new RecordingProcessStarter(
                new DefaultCodexAppServerProcessStarter(recoveryProperties), this.objectMapper);
        final CodexRecoveryInspector inspector = new CodexRecoveryInspector(
                this.objectMapper, recoveryStarter, recoveryProperties, new CodexRecoveryProtocol(this.objectMapper));

        final ProviderTurnRecoveryResult result = inspector.inspect(new AgentExecutionRecoveryInspection(
                "codex", version.get(), threadId.get(), turnId.get(), workspace));

        assertThat(result.state()).isEqualTo(ProviderTurnRecoveryState.TERMINAL);
        assertThat(result.terminalOutcome()).isEqualTo(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED);
        assertThat(recoveryStarter.methods()).contains("initialize", "initialized", "thread/turns/list");
        assertThat(recoveryStarter.methods()).doesNotContain("thread/start", "thread/resume", "turn/start");
    }

    private CodexAppServerClient client(final CodexAppServerProperties properties) {
        return new CodexAppServerClient(
                this.objectMapper,
                new DefaultCodexAppServerProcessStarter(properties),
                properties,
                new CodexRuntimeWorkspace(properties)
        );
    }

    private CodexAppServerProperties properties(final Path workspace) {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setRuntimeCwd(workspace.toString());
        properties.setRequestTimeout(Duration.ofSeconds(30));
        properties.setTurnTimeout(Duration.ofMinutes(5));
        return properties;
    }

    private static final class RecordingProcessStarter implements CodexAppServerProcessStarter {
        private final CodexAppServerProcessStarter delegate;
        private final ObjectMapper objectMapper;
        private final List<String> methods = new ArrayList<>();

        private RecordingProcessStarter(final CodexAppServerProcessStarter delegate, final ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
        }

        @Override
        public StartedCodexAppServer start(final Path workingDirectory) {
            final StartedCodexAppServer started = this.delegate.start(workingDirectory);
            return new StartedCodexAppServer(
                    new RecordingProcess(started.process(), this.objectMapper, this.methods),
                    started.command(), Instant.now());
        }

        private synchronized List<String> methods() {
            return List.copyOf(this.methods);
        }
    }

    private static final class RecordingProcess extends Process {
        private final Process delegate;
        private final OutputStream stdin;

        private RecordingProcess(final Process delegate, final ObjectMapper objectMapper, final List<String> methods) {
            this.delegate = delegate;
            this.stdin = new RecordingOutputStream(delegate.getOutputStream(), objectMapper, methods);
        }

        @Override public OutputStream getOutputStream() { return this.stdin; }
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
        @Override public ProcessHandle toHandle() { return this.delegate.toHandle(); }
    }

    private static final class RecordingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ObjectMapper objectMapper;
        private final List<String> methods;
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

        private RecordingOutputStream(final OutputStream delegate, final ObjectMapper objectMapper,
                                      final List<String> methods) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
            this.methods = methods;
        }

        @Override
        public synchronized void write(final int value) throws java.io.IOException {
            this.delegate.write(value);
            this.capture(value);
        }

        @Override
        public synchronized void write(final byte[] bytes, final int offset, final int length)
                throws java.io.IOException {
            this.delegate.write(bytes, offset, length);
            for (int index = offset; index < offset + length; index++) {
                this.capture(bytes[index]);
            }
        }

        @Override public void flush() throws java.io.IOException { this.delegate.flush(); }
        @Override public void close() throws java.io.IOException { this.delegate.close(); }

        private void capture(final int value) {
            if (value != '\n') {
                this.frame.write(value);
                return;
            }
            try {
                final JsonNode message = this.objectMapper.readTree(this.frame.toString(StandardCharsets.UTF_8));
                if (message.path("method").isTextual()) {
                    synchronized (this.methods) {
                        this.methods.add(message.path("method").asText());
                    }
                }
            } catch (final Exception ignored) {
                // The production transport owns protocol validation; this recorder is diagnostic only.
            } finally {
                this.frame.reset();
            }
        }
    }
}
