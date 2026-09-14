package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexJsonRpcTransportTest {

    private static final String MODEL_LIST = "model/list";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void correlatesConcurrentResponsesByRequestId() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);
        final CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> transport.request("first", this.objectMapper.createObjectNode(), Duration.ofSeconds(2)));
        final CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(() -> transport.request("second", this.objectMapper.createObjectNode(), Duration.ofSeconds(2)));

        final List<JsonNode> requests = List.of(this.readRequest(process), this.readRequest(process));
        final JsonNode firstRequest = requests.stream()
                .filter(request -> request.path("method").asText().equals("first"))
                .findFirst()
                .orElseThrow();
        final JsonNode secondRequest = requests.stream()
                .filter(request -> request.path("method").asText().equals("second"))
                .findFirst()
                .orElseThrow();
        process.writeStdout("{\"id\":\"" + secondRequest.path("id").asText() + "\",\"result\":{\"value\":\"second\"}}");
        process.writeStdout("{\"id\":\"" + firstRequest.path("id").asText() + "\",\"result\":{\"value\":\"first\"}}");

        assertThat(first.get(1, TimeUnit.SECONDS).path("value").asText()).isEqualTo("first");
        assertThat(second.get(1, TimeUnit.SECONDS).path("value").asText()).isEqualTo("second");
        transport.close();
    }

    @Test
    void propagatesRemoteJsonRpcErrors() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request("explode", null, Duration.ofSeconds(2)));
        final JsonNode request = this.readRequest(process);

        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"error\":{\"code\":-32000,\"message\":\"boom\"}}");

        assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexRemoteException.class);
        transport.close();
    }

    @Test
    void notificationDoesNotCorruptPendingRequest() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request(MODEL_LIST, null, Duration.ofSeconds(2)));
        final JsonNode request = this.readRequest(process);

        process.writeStdout("{\"method\":\"status/update\",\"params\":{\"state\":\"working\"}}");
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{\"data\":[]}}");

        assertThat(call.get(1, TimeUnit.SECONDS).path("data")).isEmpty();
        transport.close();
    }

    @Test
    void serverRequestReceivesUnsupportedMethodResponse() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);

        process.writeStdout("{\"id\":\"server-1\",\"method\":\"workspace/ask\",\"params\":{}}");

        final JsonNode response = this.readRequest(process);
        assertThat(response.path("id").asText()).isEqualTo("server-1");
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
        transport.close();
    }

    @Test
    void malformedJsonInvalidatesTransportAndPendingCallers() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request(MODEL_LIST, null, Duration.ofSeconds(2)));
        this.readRequest(process);

        process.writeStdout("{not-json");

        assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexTransportException.class);
        assertThat(transport.healthy()).isFalse();
    }

    @Test
    void unexpectedEofInvalidatesTransportAndPendingCallers() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request(MODEL_LIST, null, Duration.ofSeconds(2)));
        this.readRequest(process);

        process.closeStdout();

        assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexTransportException.class);
        assertThat(transport.healthy()).isFalse();
    }

    @Test
    void eofMidFrameInvalidatesTransport() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request(MODEL_LIST, null, Duration.ofSeconds(2)));
        this.readRequest(process);

        process.writeStdoutWithoutNewline("{\"id\":\"1\"");
        process.closeStdout();

        assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexTransportException.class);
        assertThat(transport.healthy()).isFalse();
    }

    @Test
    void requestTimeoutInvalidatesTransport() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request("slow", null, Duration.ofMillis(50)));
        this.readRequest(process);

        assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexTransportException.class);
        assertThat(transport.healthy()).isFalse();
        assertThat(process.destroyed()).isTrue();
    }

    @Test
    void acceptsValidFrameLargerThanSixtyFourKibWithinConfiguredLimit() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process, 4 * 1024 * 1024);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request("large", null, Duration.ofSeconds(2)));
        final JsonNode request = this.readRequest(process);
        final String payload = "x".repeat(70 * 1024);

        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{\"payload\":\"" + payload + "\"}}");

        assertThat(call.get(1, TimeUnit.SECONDS).path("payload").asText()).hasSize(70 * 1024);
        transport.close();
    }

    @Test
    void configuredFrameOverflowInvalidatesTransport() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process, 64);
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> transport.request("large", null, Duration.ofSeconds(2)));
        this.readRequest(process);

        process.writeStdoutWithoutNewline("{\"id\":\"1\",\"result\":{\"payload\":\"" + "x".repeat(128));

        assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexTransportException.class);
        assertThat(transport.healthy()).isFalse();
    }

    @Test
    void closeTerminatesProcessGracefully() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(true, true);
        final CodexJsonRpcTransport transport = this.transport(process);

        transport.close();

        assertThat(process.destroyed()).isTrue();
        assertThat(process.forciblyDestroyed()).isFalse();
        assertThat(process.awaitExit(Duration.ofSeconds(1))).isTrue();
        assertThat(transport.cleanupComplete()).isTrue();
    }

    @Test
    void closeForcesProcessAfterGracefulTimeout() {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexJsonRpcTransport transport = this.transport(process);

        transport.close();

        assertThat(process.destroyed()).isTrue();
        assertThat(process.forciblyDestroyed()).isTrue();
        assertThat(transport.cleanupComplete()).isTrue();
    }

    @Test
    void closeTerminatesTheExternalProcessTree() throws Exception {
        final Process process = new ProcessBuilder("/bin/sh", "-c", "sleep 30 & wait").start();
        final long discoveryDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        List<ProcessHandle> children = List.of();
        while (children.isEmpty() && System.nanoTime() < discoveryDeadline) {
            children = process.descendants().toList();
            Thread.onSpinWait();
        }
        assertThat(children).isNotEmpty();
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setGracefulTerminateTimeout(Duration.ofMillis(100));
        properties.setForceKillTimeout(Duration.ofSeconds(1));
        final CodexJsonRpcTransport transport = new CodexJsonRpcTransport(this.objectMapper,
                new StartedCodexAppServer(process, List.of("test-process-tree"), Instant.now()), properties);

        transport.close();

        assertThat(process.isAlive()).isFalse();
        assertThat(children).noneMatch(ProcessHandle::isAlive);
    }

    @Test
    void closeUsesTreeCapturedBeforeWrapperExitToTerminateInheritedPipeChild() throws Exception {
        final Process delegate = new ProcessBuilder(
                "/bin/sh", "-c", "sleep 30 <&0 & read ignored").start();
        final ProcessHandle child = awaitChild(delegate, Duration.ofSeconds(1));
        final Process process = new AwaitRootExitOnCloseProcess(delegate);
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setGracefulTerminateTimeout(Duration.ofMillis(100));
        properties.setForceKillTimeout(Duration.ofSeconds(1));
        final CodexJsonRpcTransport transport = new CodexJsonRpcTransport(this.objectMapper,
                new StartedCodexAppServer(process, List.of("test-wrapper-tree"), Instant.now()), properties);

        try {
            transport.close();

            assertThat(awaitExit(delegate.toHandle(), Duration.ofSeconds(1))).isTrue();
            assertThat(awaitExit(child, Duration.ofSeconds(1))).isTrue();
            assertThat(transport.cleanupComplete()).isTrue();
        } finally {
            child.destroyForcibly();
            delegate.toHandle().destroyForcibly();
        }
    }

    @Test
    void processStillAliveAfterForceKillTimeoutFailsCleanup() {
        final FakeCodexProcess process = new FakeCodexProcess(false, false);
        final CodexJsonRpcTransport transport = this.transport(process);

        assertThatThrownBy(transport::close)
                .isInstanceOf(CodexTransportException.class)
                .hasMessageContaining("force kill timeout");
        assertThat(transport.cleanupComplete()).isFalse();
        process.terminateNow();
    }

    private CodexJsonRpcTransport transport(final FakeCodexProcess process) {
        return this.transport(process, 4 * 1024 * 1024);
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

    private static final class AwaitRootExitOnCloseProcess extends Process {
        private final Process delegate;
        private final OutputStream output;

        private AwaitRootExitOnCloseProcess(final Process delegate) {
            this.delegate = delegate;
            this.output = new OutputStream() {
                @Override public void write(final int value) throws IOException {
                    AwaitRootExitOnCloseProcess.this.delegate.getOutputStream().write(value);
                }
                @Override public void write(final byte[] values, final int offset, final int length)
                        throws IOException {
                    AwaitRootExitOnCloseProcess.this.delegate.getOutputStream().write(values, offset, length);
                }
                @Override public void flush() throws IOException {
                    AwaitRootExitOnCloseProcess.this.delegate.getOutputStream().flush();
                }
                @Override public void close() throws IOException {
                    AwaitRootExitOnCloseProcess.this.delegate.getOutputStream().close();
                    try {
                        AwaitRootExitOnCloseProcess.this.delegate.waitFor();
                    } catch (final InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted waiting for wrapper exit", exception);
                    }
                }
            };
        }

        @Override public OutputStream getOutputStream() { return this.output; }
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

    private CodexJsonRpcTransport transport(final FakeCodexProcess process, final int frameLimitBytes) {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setStdioFrameLimitBytes(frameLimitBytes);
        properties.setGracefulTerminateTimeout(Duration.ofMillis(10));
        properties.setForceKillTimeout(Duration.ofMillis(100));
        return new CodexJsonRpcTransport(this.objectMapper, new StartedCodexAppServer(process, List.of("codex", "app-server", "--stdio"), Instant.now()), properties);
    }

    private JsonNode readRequest(final FakeCodexProcess process) throws Exception {
        return this.objectMapper.readTree(process.readRequest());
    }
}
