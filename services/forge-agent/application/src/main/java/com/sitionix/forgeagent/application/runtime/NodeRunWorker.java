package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class NodeRunWorker {

    static final String AGENT_EXECUTOR_FAILED = "AGENT_EXECUTOR_FAILED";
    static final String AGENT_EXECUTOR_INVALID_OUTPUT = "AGENT_EXECUTOR_INVALID_OUTPUT";

    private final NodeRunRepository nodeRunRepository;
    private final NodeRunLifecycle lifecycle;
    private final AgentExecutor agentExecutor;
    private final ExecutorService executorService;

    public void poll() {
        for (final UUID nodeRunId : this.nodeRunRepository.findPendingIds()) {
            this.lifecycle.tryStart(nodeRunId).ifPresent(this::submit);
        }
    }

    private void submit(final NodeExecutionClaim claim) {
        this.executorService.submit(() -> {
            final NodeRunOutput output;
            try {
                output = this.agentExecutor.execute(claim);
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
                this.lifecycle.fail(claim.nodeRunId(), new NodeRunFailure(AGENT_EXECUTOR_FAILED, this.failureMessage(exception)));
                return;
            }
            if (output == null) {
                this.lifecycle.fail(claim.nodeRunId(), new NodeRunFailure(AGENT_EXECUTOR_INVALID_OUTPUT, "Agent execution returned no output."));
                return;
            }
            this.lifecycle.succeed(claim.nodeRunId(), output);
        });
    }

    private String failureMessage(final RuntimeException exception) {
        final String message = exception.getMessage();
        return message == null || message.isBlank() ? "Agent execution failed." : message;
    }
}
