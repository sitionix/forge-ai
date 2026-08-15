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
                                             final RunNode targetNode) {
        return this.nodeRunRepository.findByWorkflowRunIdAndExecutionFrameIdAndSourceNodeId(
                        workflowRun.id(),
                        activationFrame.id(),
                        targetNode.sourceNodeId()
                )
                .map(existing -> this.createChildFrame(workflowRun, activationFrame))
                .orElse(activationFrame);
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
