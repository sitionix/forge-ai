package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NodeRunWorker {

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
                this.lifecycle.fail(claim.nodeRunId(), new NodeRunFailure(
                        "AGENT_EXECUTOR_FAILED",
                        exception.getMessage() == null ? "Agent execution failed." : exception.getMessage()
                ));
                return;
            }
            this.lifecycle.succeed(claim.nodeRunId(), output);
        });
    }
}
