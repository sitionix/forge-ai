package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspector;
import com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.port.AgentExecutionEventRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real provider processes with a read-only wire recorder; accessible only to boot tests. */
public final class LiveCodexRecoveryFixture {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodexAppServerProperties properties = new CodexAppServerProperties();
    private final List<RecordedProcess> executionProcesses = new ArrayList<>();
    private final List<RecordedProcess> inspectionProcesses = new ArrayList<>();

    public LiveCodexRecoveryFixture(final Path workspace) {
        this.properties.setRuntimeCwd(workspace.toString());
        this.properties.setRequestTimeout(Duration.ofSeconds(15));
        this.properties.setTurnTimeout(Duration.ofMinutes(5));
    }

    public void executeTrackedDurableTurn(final NodeExecutionClaim claim,
                                         final AgentSessionLeaseService leases,
                                         final AgentExecutionEventRepository events) throws IOException {
        final var client = new CodexAppServerClient(this.mapper, this.starter(this.executionProcesses),
                this.properties, new CodexRuntimeWorkspace(this.properties));
        try {
            client.executeDurable(new CodexTurnRequest(
                    "Return JSON with answer set to recovery-ready.",
                    "Return only JSON matching the supplied schema. Do not invoke tools.",
                    claim.executionModel().modelId(), null,
                    this.mapper.readTree(claim.outputSchema().jsonObject()), claim.executionWorkspace()),
                    null, new CodexExecutionIdentityCallbacks() {
                        @Override
                        public void conversationStarted(final String threadId, final String providerVersion) {
                            leases.persistConversation(claim.agentSessionClaim(), threadId, providerVersion);
                        }

                        @Override
                        public void turnStarted(final String turnId) {
                            leases.persistTurn(claim.agentSessionClaim(), turnId);
                            if (!events.activate(claim.agentSessionClaim())) {
                                throw new IllegalStateException("Tracked live turn lost ownership before capture activation.");
                            }
                        }
                    });
            // Simulate the crash boundary: neither completion capture nor Forge result/routing is committed.
        } finally {
            client.close();
        }
    }

    public AgentExecutionRecoveryInspector inspector() {
        final Clock clock = Clock.systemUTC();
        return new CodexRecoveryInspector(this.mapper, this.starter(this.inspectionProcesses), this.properties,
                new CodexRecoveryProtocol(this.mapper, clock), clock);
    }

    public List<RecordedProcess> executionProcesses() { return List.copyOf(this.executionProcesses); }
    public List<RecordedProcess> inspectionProcesses() { return List.copyOf(this.inspectionProcesses); }

    private CodexAppServerProcessStarter starter(final List<RecordedProcess> processes) {
        final var delegate = new DefaultCodexAppServerProcessStarter(this.properties);
        return cwd -> {
            final var started = delegate.start(cwd);
            final var recorded = new RecordedProcess(started.process(), this.mapper);
            processes.add(recorded);
            return new StartedCodexAppServer(recorded, started.command(), started.startedAt());
        };
    }

    public static final class RecordedProcess extends Process {
        private final Process delegate;
        private final OutputStream stdin;
        private final List<JsonNode> requests = new ArrayList<>();

        private RecordedProcess(final Process delegate, final ObjectMapper mapper) {
            this.delegate = delegate;
            this.stdin = new OutputStream() {
                private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

                @Override
                public synchronized void write(final int value) throws IOException {
                    delegate.getOutputStream().write(value);
                    this.capture(value);
                }

                @Override
                public synchronized void write(final byte[] bytes, final int offset, final int length) throws IOException {
                    delegate.getOutputStream().write(bytes, offset, length);
                    for (int index = offset; index < offset + length; index++) this.capture(bytes[index]);
                }

                private void capture(final int value) throws IOException {
                    if (value != '\n') {
                        this.frame.write(value);
                        return;
                    }
                    final JsonNode request = mapper.readTree(this.frame.toString(StandardCharsets.UTF_8));
                    synchronized (requests) { requests.add(request); }
                    this.frame.reset();
                }

                @Override public void flush() throws IOException { delegate.getOutputStream().flush(); }
                @Override public void close() throws IOException { delegate.getOutputStream().close(); }
            };
        }

        public List<JsonNode> requests() { synchronized (this.requests) { return List.copyOf(this.requests); } }
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
}
