package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.port.AgentExecutionDispatchGuard;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native provider handshake followed by an unread inherited stdin pipe; only killing the tree unblocks it. */
public final class UnreadCodexDispatchFixture implements AutoCloseable {
    private final Process process;
    private final CodexAppServerClient client;
    private final CodexAgentExecutor executor;
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private volatile boolean guardReleased;
    private volatile boolean writeCompleted;
    private volatile boolean writeInTransaction;

    public UnreadCodexDispatchFixture(final AgentSessionLeaseService leases, final AgentExecutionDispatchGuard guard) throws IOException {
        this.process = new ProcessBuilder("/bin/sh", "-c", """
                IFS= read -r initialize
                printf '%s\\n' '{"id":"1","result":{"userAgent":"codex/0.154.0"}}'
                IFS= read -r initialized
                IFS= read -r thread
                sleep 30 <&0 &
                child=$!
                printf '%s\\n' '{"id":"2","result":{"thread":{"id":"thread-unread"}}}'
                wait "$child"
                """).start();
        final var properties = new CodexAppServerProperties();
        properties.setRequestTimeout(Duration.ofMillis(300));
        properties.setGracefulTerminateTimeout(Duration.ofMillis(100));
        properties.setForceKillTimeout(Duration.ofSeconds(1));
        final var mapper = new ObjectMapper();
        this.client = new CodexAppServerClient(mapper,
                cwd -> new StartedCodexAppServer(this.process, List.of("unread-provider"), Instant.now()),
                properties, new CodexRuntimeWorkspace(properties));
        this.executor = new CodexAgentExecutor(mapper, this.client, leases, null, (claim, write) -> {
            try {
                guard.dispatch(claim, () -> {
                    this.writeInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
                    this.writeEntered.countDown();
                    write.run();
                    this.writeCompleted = true;
                });
            } finally { this.guardReleased = true; }
        });
    }

    public void execute(final NodeExecutionClaim claim) {
        this.executor.execute(new NodeExecutionClaim(claim.workflowRunId(), claim.nodeRunId(), claim.sourceAgentId(),
                claim.workflowInput(), claim.agentName(), claim.agentInstructions(), claim.outputSchema(), claim.executionModel(),
                new NodeInputEnvelope("x".repeat(8 * 1024 * 1024), null, List.of()), claim.availableOutputs(),
                claim.executionWorkspace(), claim.agentSessionClaim()));
    }

    public boolean awaitWrite() throws InterruptedException { return this.writeEntered.await(2, TimeUnit.SECONDS); }
    public boolean guardReleased() { return this.guardReleased; }
    public boolean writeCompleted() { return this.writeCompleted; }
    public boolean writeInTransaction() { return this.writeInTransaction; }
    public Process process() { return this.process; }

    @Override public void close() {
        this.process.descendants().forEach(ProcessHandle::destroyForcibly);
        this.process.toHandle().destroyForcibly();
        this.client.close();
    }
}
