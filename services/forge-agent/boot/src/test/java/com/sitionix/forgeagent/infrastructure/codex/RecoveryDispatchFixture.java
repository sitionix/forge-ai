package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.port.AgentExecutionDispatchGuard;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real executor/client/transport with a controllable provider wire and real lease service. */
public final class RecoveryDispatchFixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WireProcess process;
    private final CodexAppServerClient client;
    private final CodexAgentExecutor executor;
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private volatile boolean turnWriteInTransaction;
    private volatile JsonNode turnRequest;

    public RecoveryDispatchFixture(final AgentSessionLeaseService leases, final AgentExecutionDispatchGuard guard, final Runnable afterConversation,
                                   final Runnable beforeTurnWrite) throws IOException {
        this.process = new WireProcess(beforeTurnWrite);
        final var properties = new CodexAppServerProperties();
        properties.setRequestTimeout(Duration.ofSeconds(10));
        properties.setTurnTimeout(Duration.ofSeconds(10));
        this.client = new CodexAppServerClient(this.mapper,
                cwd -> new StartedCodexAppServer(this.process, List.of("fake-codex"), Instant.now()),
                properties, new CodexRuntimeWorkspace(properties));
        final var proxy = new ProxyFactory(leases);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            final Object result = invocation.proceed();
            if ("persistConversation".equals(invocation.getMethod().getName())) afterConversation.run();
            return result;
        });
        this.executor = new CodexAgentExecutor(this.mapper, this.client, (AgentSessionLeaseService) proxy.getProxy(), null, guard);
    }

    public void execute(final NodeExecutionClaim claim) { this.executor.execute(claim); }
    public List<String> methods() { return List.copyOf(this.methods); }
    public boolean turnWriteInTransaction() { return this.turnWriteInTransaction; }
    public void replyTurn() throws IOException {
        this.reply(this.turnRequest, "{\"turn\":{\"id\":\"turn-fence\"}}");
    }
    @Override public void close() { this.process.destroy(); this.client.close(); }

    private void reply(final JsonNode request, final String result) throws IOException {
        this.process.stdoutWriter.write(("{\"id\":\"" + request.path("id").asText() + "\",\"result\":" + result + "}\n")
                .getBytes(StandardCharsets.UTF_8));
        this.process.stdoutWriter.flush();
    }

    private final class WireProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream(8192);
        private final PipedOutputStream stdoutWriter = new PipedOutputStream(this.stdout);
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile boolean alive = true;
        private final OutputStream stdin;

        WireProcess(final Runnable beforeTurnWrite) throws IOException {
            this.stdin = new OutputStream() {
                private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
                @Override public void write(final int value) throws IOException {
                    if (value != '\n') { this.frame.write(value); return; }
                    final JsonNode request = mapper.readTree(this.frame.toString(StandardCharsets.UTF_8));
                    this.frame.reset();
                    final String method = request.path("method").asText();
                    if ("turn/start".equals(method)) {
                        turnWriteInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
                        beforeTurnWrite.run();
                        turnRequest = request;
                    }
                    methods.add(method);
                    switch (method) {
                        case "initialize" -> reply(request, "{\"userAgent\":\"codex/0.154.0\"}");
                        case "thread/start" -> reply(request, "{\"thread\":{\"id\":\"thread-fence\"}}");
                        default -> { }
                    }
                }
            };
        }
        @Override public OutputStream getOutputStream() { return this.stdin; }
        @Override public InputStream getInputStream() { return this.stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public boolean isAlive() { return this.alive; }
        @Override public long pid() { return 918273L; }
        @Override public int waitFor() throws InterruptedException { this.exited.await(); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return this.exited.await(timeout, unit); }
        @Override public int exitValue() { if (this.alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() {
            this.alive = false;
            try { this.stdoutWriter.close(); } catch (IOException ignored) { }
            this.exited.countDown();
        }
        @Override public Process destroyForcibly() { this.destroy(); return this; }
    }
}
