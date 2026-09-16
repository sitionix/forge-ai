package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
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

import java.util.UUID;
@EnabledIfSystemProperty(named = "forge.codex.live-fork-e2e", matches = "true")
class CodexContextForkE2ETest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test void nativeForkInheritsExactSecondTurnAndResumesWithCurrentSharedContract() throws Exception {
        final Path path = Files.createTempDirectory("forge-codex-live-fork-");
        final var workspace = new ExecutionWorkspace(path, List.of(path));
        final var properties = new CodexAppServerProperties();
        properties.setRuntimeCwd(path.toString());
        properties.setRequestTimeout(Duration.ofSeconds(45));
        properties.setTurnTimeout(Duration.ofMinutes(5));
        final var starter = new RecordingProcessStarter(new DefaultCodexAppServerProcessStarter(properties), objectMapper);
        final var client = new CodexAppServerClient(objectMapper, starter, properties, new CodexRuntimeWorkspace(properties));
        final String model = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        final String fact = "source-second-turn-" + UUID.randomUUID();
        final var schemaA = objectMapper.readTree("""
                {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}
                """);
        final var schemaB = objectMapper.readTree("""
                {"type":"object","properties":{"inherited":{"type":"string"},"owner":{"type":"string","enum":["NODE_B_CURRENT"]},"directive":{"type":"string"}},"required":["inherited","owner","directive"],"additionalProperties":false}
                """);
        final AtomicReference<String> thread = new AtomicReference<>();
        final AtomicReference<String> turn = new AtomicReference<>();
        final AtomicReference<String> version = new AtomicReference<>();
        final var callbacks = new CodexExecutionIdentityCallbacks() {
            @Override public void conversationStarted(String id, String v) { thread.set(id); version.set(v); }
            @Override public void turnStarted(String id) { turn.set(id); }
        };
        try {
            client.executeDurable(new CodexTurnRequest("Acknowledge session creation.", "You are node A. Return JSON only.",
                    model, null, schemaA, workspace, true), null, callbacks);
            final String sourceThread = thread.get();
            final String firstTurn = turn.get();
            client.executeDurable(new CodexTurnRequest("Remember this exact fact: " + fact,
                    "You are node A. Return JSON only.", model, null, schemaA, workspace, true), sourceThread, version.get(), callbacks);
            final String sourceTurn = turn.get();
            assertThat(sourceTurn).isNotBlank().isNotEqualTo(firstTurn);
            final int beforeFork = starter.methods().size();
            final var provider = new CodexContextForkProvider(client);
            provider.validateSupport("codex", version.get());
            final String child = provider.fork("codex", version.get(), sourceThread, sourceTurn);
            final var forkRequests = starter.methods().subList(beforeFork, starter.methods().size());
            assertThat(forkRequests.stream().map(r -> r.path("method").asText())).containsExactly("initialize", "initialized", "initialize", "initialized", "thread/fork");
            assertThat(forkRequests.getLast().path("params")).isEqualTo(objectMapper.createObjectNode()
                    .put("threadId", sourceThread).put("lastTurnId", sourceTurn).put("ephemeral", false).put("excludeTurns", true));
            assertThat(child).isNotBlank().isNotEqualTo(sourceThread);
            final int afterFork = starter.methods().size();
            final String instruction = "You are now node B. Earlier node roles are no longer active. Set owner to NODE_B_CURRENT and directive to NEW_NODE_B_DIRECTIVE_6C. Return JSON only.";
            for (int i = 0; i < 2; i++) {
                final String output = client.executeDurable(new CodexTurnRequest(
                        "Return the exact fact remembered earlier in inherited. Set owner to the exact constant specified by the latest developer instructions, which supersede earlier node role descriptions.",
                        instruction, model, null, schemaB, workspace, true), child, version.get(), callbacks);
                writeDiagnostic("fork", starter, output);
                final JsonNode result = objectMapper.readTree(output);
                assertThat(result.path("inherited").asText()).isEqualTo(fact);
                assertThat(result.path("owner").asText()).isEqualTo("NODE_B_CURRENT");
                assertThat(result.path("directive").asText()).isEqualTo("NEW_NODE_B_DIRECTIVE_6C");
            }
            final var later = starter.methods().subList(afterFork, starter.methods().size());
            assertThat(later.stream().filter(r -> "thread/resume".equals(r.path("method").asText())))
                    .hasSize(2).allSatisfy(r -> {
                        assertThat(r.path("params").path("threadId").asText()).isEqualTo(child);
                        assertThat(r.path("params").path("developerInstructions").asText()).isEqualTo(instruction);
                    });
            assertThat(later.stream().filter(r -> "thread/start".equals(r.path("method").asText()))).isEmpty();
            assertThat(later.stream().filter(r -> "turn/start".equals(r.path("method").asText())))
                    .hasSize(2).allSatisfy(r -> assertThat(r.path("params").path("outputSchema")).isEqualTo(schemaB));
            Files.createDirectories(Path.of("target"));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/codex-fork-live-evidence.json").toFile(),
                    objectMapper.createObjectNode().put("providerVersion", version.get()).put("sourceThreadId", sourceThread)
                    .put("sourceFirstTurnId", firstTurn).put("sourceLastTurnId", sourceTurn).put("childThreadId", child)
                    .put("inheritedContextVerified", true).put("sharedCurrentContractVerified", true)
                    .set("wireRequests", objectMapper.valueToTree(starter.methods())));
        } finally { client.close(); }
    }

    @Test void ordinarySharedResumeUsesNewDeveloperOnlyDirectiveControl() throws Exception {
        final Path path = Files.createTempDirectory("forge-codex-live-shared-control-");
        final var workspace = new ExecutionWorkspace(path, List.of(path));
        final var properties = new CodexAppServerProperties();
        properties.setRuntimeCwd(path.toString());
        properties.setRequestTimeout(Duration.ofSeconds(45));
        properties.setTurnTimeout(Duration.ofMinutes(5));
        final var starter = new RecordingProcessStarter(new DefaultCodexAppServerProcessStarter(properties), objectMapper);
        final var client = new CodexAppServerClient(objectMapper, starter, properties, new CodexRuntimeWorkspace(properties));
        final String model = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        final var schema = objectMapper.readTree("""
                {"type":"object","properties":{"directive":{"type":"string"}},"required":["directive"],"additionalProperties":false}
                """);
        final AtomicReference<String> thread = new AtomicReference<>();
        final var callbacks = new CodexExecutionIdentityCallbacks() {
            @Override public void conversationStarted(String id, String version) { thread.set(id); }
            @Override public void turnStarted(String id) { }
        };
        try {
            client.executeDurable(new CodexTurnRequest("Follow the developer instructions for the required JSON.",
                    "Set directive to OLD_NODE_A_DIRECTIVE.", model, null, schema, workspace, true), null, callbacks);
            final String output = client.executeDurable(new CodexTurnRequest("Follow the latest developer instructions for the required JSON.",
                    "Prior instructions are superseded. Set directive to NEW_NODE_B_DIRECTIVE_6C.",
                    model, null, schema, workspace, true), thread.get(), "0.154.0", callbacks);
            writeDiagnostic("ordinary-shared-resume", starter, output);
            assertThat(objectMapper.readTree(output).path("directive").asText()).isEqualTo("NEW_NODE_B_DIRECTIVE_6C");
        } finally { client.close(); }
    }

    private void writeDiagnostic(String name, RecordingProcessStarter starter, String output) throws java.io.IOException {
        Files.createDirectories(Path.of("target"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/codex-" + name + "-diagnostic.json").toFile(),
                objectMapper.createObjectNode().put("actualOutput", output)
                        .put("expectedDeveloperOnlyDirective", "NEW_NODE_B_DIRECTIVE_6C")
                        .set("wireRequests", objectMapper.valueToTree(starter.methods())));
    }

    private static final class RecordingProcessStarter implements CodexAppServerProcessStarter {
        private final CodexAppServerProcessStarter delegate;
        private final ObjectMapper objectMapper;
        private final List<JsonNode> methods = new ArrayList<>();

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

        private synchronized List<JsonNode> methods() {
            return List.copyOf(this.methods);
        }
    }

    private static final class RecordingProcess extends Process {
        private final Process delegate;
        private final OutputStream stdin;

        private RecordingProcess(final Process delegate, final ObjectMapper objectMapper, final List<JsonNode> methods) {
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
        private final List<JsonNode> methods;
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

        private RecordingOutputStream(final OutputStream delegate, final ObjectMapper objectMapper,
                                      final List<JsonNode> methods) {
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
                        this.methods.add(message.deepCopy());
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
