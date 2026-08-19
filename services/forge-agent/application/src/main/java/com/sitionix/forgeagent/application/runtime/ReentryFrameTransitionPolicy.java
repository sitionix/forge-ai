package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReentryFrameTransitionPolicy implements FrameTransitionPolicy {

    private final NodeRunRepository nodeRunRepository;
    private final ExecutionFrameRepository frameRepository;
    private final Clock clock;

    @Override
    public ExecutionFrame frameForActivation(final WorkflowRun workflowRun,
                                             final ExecutionFrame activationFrame,
                                             final RunNode targetNode,
                                             final UUID targetInputPortId,
                                             final UUID repositoryId) {
        final boolean reentry = this.nodeRunRepository.findByWorkflowRunIdAndExecutionFrameId(workflowRun.id(), activationFrame.id()).stream()
                .anyMatch(run -> run.sourceNodeId().equals(targetNode.sourceNodeId())
                        && java.util.Objects.equals(run.repositoryId(), repositoryId));
        if (!reentry) {
            return activationFrame;
        }
        return this.nodeRunRepository.findByWorkflowRunId(workflowRun.id()).stream()
                .filter(run -> activationFrame.id().equals(run.activationFrameId()))
                .filter(run -> !activationFrame.id().equals(run.executionFrameId()))
                .filter(run -> run.sourceNodeId().equals(targetNode.sourceNodeId()))
                .filter(run -> targetInputPortId.equals(run.enteredViaInputPortId()))
                .map(com.sitionix.forgeagent.domain.model.NodeRun::executionFrameId)
                .distinct()
                .map(this.frameRepository::findById)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseGet(() -> this.createChildFrame(workflowRun, activationFrame));
    }

    private ExecutionFrame createChildFrame(final WorkflowRun workflowRun, final ExecutionFrame parentFrame) {
        return this.frameRepository.save(new ExecutionFrame(
                UUID.randomUUID(),
                workflowRun.id(),
                parentFrame.id(),
                Instant.now(this.clock)
        ));
    }
}
