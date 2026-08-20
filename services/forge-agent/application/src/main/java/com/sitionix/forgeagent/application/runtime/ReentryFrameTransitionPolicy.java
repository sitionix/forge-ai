package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
                                             final UUID targetInputPortId) {
        final java.util.Optional<ExecutionFrame> existingChild = this.nodeRunRepository.findByWorkflowRunId(workflowRun.id()).stream()
                .filter(run -> activationFrame.id().equals(run.activationFrameId()))
                .filter(run -> !activationFrame.id().equals(run.executionFrameId()))
                .filter(run -> run.sourceNodeId().equals(targetNode.sourceNodeId()))
                .filter(run -> targetInputPortId.equals(run.enteredViaInputPortId()))
                .map(com.sitionix.forgeagent.domain.model.NodeRun::executionFrameId)
                .distinct()
                .map(this.frameRepository::findById)
                .flatMap(java.util.Optional::stream)
                .findFirst();
        if (existingChild.isPresent()) {
            return existingChild.get();
        }
        final List<NodeRun> activationFrameRuns = this.nodeRunRepository
                .findByWorkflowRunIdAndExecutionFrameId(workflowRun.id(), activationFrame.id());
        final boolean existingParentWave = activationFrameRuns.stream()
                .filter(run -> activationFrame.id().equals(run.activationFrameId()))
                .filter(run -> run.sourceNodeId().equals(targetNode.sourceNodeId()))
                .anyMatch(run -> targetInputPortId.equals(run.enteredViaInputPortId()));
        if (existingParentWave) {
            return activationFrame;
        }
        final boolean reentry = activationFrameRuns.stream()
                .anyMatch(run -> run.sourceNodeId().equals(targetNode.sourceNodeId()));
        return reentry ? this.createChildFrame(workflowRun, activationFrame) : activationFrame;
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
