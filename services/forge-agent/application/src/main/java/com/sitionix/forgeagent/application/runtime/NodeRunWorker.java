package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NodeRunWorker {

    static final String AGENT_EXECUTOR_FAILED = "AGENT_EXECUTOR_FAILED";
    static final String AGENT_EXECUTOR_INVALID_OUTPUT = "AGENT_EXECUTOR_INVALID_OUTPUT";

    private final NodeRunRepository nodeRunRepository;
    private final NodeRunLifecycle lifecycle;
    private final AgentExecutor agentExecutor;
    private final ExecutorService executorService;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AgentSessionLeaseService sessionLeaseService;

    public NodeRunWorker(NodeRunRepository nodeRunRepository, NodeRunLifecycle lifecycle, AgentExecutor agentExecutor,
                         ExecutorService executorService, ScheduledExecutorService heartbeatExecutor,
                         AgentSessionLeaseService sessionLeaseService) {
        this.nodeRunRepository=nodeRunRepository; this.lifecycle=lifecycle; this.agentExecutor=agentExecutor;
        this.executorService=executorService; this.heartbeatExecutor=heartbeatExecutor; this.sessionLeaseService=sessionLeaseService;
    }

    NodeRunWorker(NodeRunRepository nodeRunRepository, NodeRunLifecycle lifecycle, AgentExecutor agentExecutor,
                  ExecutorService executorService) {
        this(nodeRunRepository, lifecycle, agentExecutor, executorService,
                Executors.newSingleThreadScheduledExecutor(r -> { var thread=new Thread(r,"agent-session-heartbeat-test"); thread.setDaemon(true); return thread; }), null);
    }

    public void poll() {
        this.lifecycle.recoverExpiredSessions();
        for (final UUID nodeRunId : this.nodeRunRepository.findPendingIds()) {
            this.lifecycle.tryStart(nodeRunId).ifPresent(this::submit);
        }
    }

    private void submit(final NodeExecutionClaim claim) {
        this.executorService.submit(() -> {
            final AgentSessionHeartbeat heartbeat = claim.agentSessionClaim() == null ? null
                    : new AgentSessionHeartbeat(this.sessionLeaseService, claim.agentSessionClaim(), this.heartbeatExecutor,
                            () -> this.agentExecutor.cancel(claim));
            final AgentExecutionResult result;
            try {
                result = this.agentExecutor.execute(claim);
                if (heartbeat != null) heartbeat.verifyOwnership();
            } catch (final RuntimeException exception) {
                log.error(
                        "Agent executor failed nodeRunId={} workflowRunId={} agentId={} agentName={} providerId={} modelId={}",
                        claim.nodeRunId(),
                        claim.workflowRunId(),
                        claim.sourceAgentId(),
                        claim.agentName(),
                        claim.executionModel().providerId(),
                        claim.executionModel().modelId(),
                        exception
                );
                try {
                    this.fail(claim, new NodeRunFailure(this.failureCode(exception), this.failureMessage(exception)));
                } finally {
                    if (heartbeat != null) heartbeat.close();
                }
                return;
            }
            if (heartbeat != null) heartbeat.close();
            if (result == null || result.output() == null) {
                this.fail(claim, new NodeRunFailure(AGENT_EXECUTOR_INVALID_OUTPUT, "Agent execution returned no output."));
                return;
            }
            if (claim.agentSessionClaim() == null) this.lifecycle.succeed(claim.nodeRunId(), result);
            else this.lifecycle.succeed(claim.nodeRunId(), result, claim.agentSessionClaim());
        });
    }

    private String failureMessage(final RuntimeException exception) {
        final String message = exception.getMessage();
        return message == null || message.isBlank() ? "Agent execution failed." : message;
    }

    private String failureCode(final RuntimeException exception) {
        if (exception instanceof com.sitionix.forgeagent.domain.exception.ForgeAgentException typed) return typed.code();
        return AGENT_EXECUTOR_FAILED;
    }

    private void fail(final NodeExecutionClaim claim, final NodeRunFailure failure) {
        if (claim.agentSessionClaim() == null) this.lifecycle.fail(claim.nodeRunId(), failure);
        else this.lifecycle.fail(claim.nodeRunId(), failure, claim.agentSessionClaim());
    }
}
