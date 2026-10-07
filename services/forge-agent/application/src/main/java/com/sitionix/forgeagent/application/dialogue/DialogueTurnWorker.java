package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.AgentSessionHeartbeat;
import com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DialogueTurnWorker {
    private final DialogueRepository dialogues;
    private final DialogueTurnLifecycle lifecycle;
    private final AgentExecutor executor;
    private final AgentSessionLeaseService leases;
    private final ExecutorService executionPool;
    private final ScheduledExecutorService heartbeatPool;

    public void poll() {
        for (var turnId : dialogues.queuedTurnIds()) {
            try {
                lifecycle.claim(turnId).ifPresent(request -> executionPool.submit(() -> this.execute(request)));
            } catch (RuntimeException failure) {
                log.warn("Dialogue claim will be retried. turnId={} failureClass={}",turnId,failure.getClass().getSimpleName());
            }
        }
    }

    private void execute(final DialogueExecutionRequest request) {
        final var claim = request.executionClaim();
        try (var heartbeat = new AgentSessionHeartbeat(leases,claim.agentSessionClaim(),heartbeatPool,() -> executor.cancel(claim))) {
            final var result = executor.executeDialogue(request);
            heartbeat.verifyOwnership();
            lifecycle.succeed(request,result);
        } catch (RuntimeException failure) {
            try { lifecycle.fail(request,failure); }
            catch (RuntimeException fenced) {
                log.warn("Dialogue failure could not be applied. nodeRunId={} failureClass={}",claim.nodeRunId(),fenced.getClass().getSimpleName());
            }
        }
    }
}
