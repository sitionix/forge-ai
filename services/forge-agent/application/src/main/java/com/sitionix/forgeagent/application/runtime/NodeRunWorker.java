package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;

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
            } catch (final AgentExecutionException exception) {
                this.lifecycle.fail(claim.nodeRunId(), new NodeRunFailure(exception.code(), exception.safeMessage()));
                return;
            } catch (final RuntimeException exception) {
                this.lifecycle.fail(claim.nodeRunId(), new NodeRunFailure(AGENT_EXECUTOR_FAILED, "Agent execution failed."));
                return;
            }
            if (output == null) {
                this.lifecycle.fail(claim.nodeRunId(), new NodeRunFailure(AGENT_EXECUTOR_INVALID_OUTPUT, "Agent execution returned no output."));
                return;
            }
            this.lifecycle.succeed(claim.nodeRunId(), output);
        });
    }
}
