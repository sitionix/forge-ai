package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NodeRunFactory {

    private final Clock clock;

    public NodeRun root(final WorkflowRun workflowRun, final ExecutionFrame executionFrame, final RunNode runNode) {
        return this.create(workflowRun, executionFrame, runNode, null, null);
    }

    public NodeRun activated(final WorkflowRun workflowRun,
                             final ExecutionFrame executionFrame,
                             final ExecutionFrame activationFrame,
                             final RunNode runNode,
                             final UUID enteredViaInputPortId) {
        return this.create(workflowRun, executionFrame, runNode, activationFrame.id(), enteredViaInputPortId);
    }

    private NodeRun create(final WorkflowRun workflowRun,
                           final ExecutionFrame executionFrame,
                           final RunNode runNode,
                           final UUID activationFrameId,
                           final UUID enteredViaInputPortId) {
        return new NodeRun(
                UUID.randomUUID(),
                workflowRun.id(),
                runNode.sourceNodeId(),
                runNode.sourceAgentId(),
                runNode.agentName(),
                runNode.agentInstructions(),
                runNode.agentOutputSchema(),
                runNode.inputMode(),
                runNode.position(),
                executionFrame.id(),
                enteredViaInputPortId,
                activationFrameId,
                null,
                NodeRunStatus.PENDING,
                null,
                null,
                runNode.executionModel(),
                Instant.now(this.clock),
                null,
                null
        );
    }
}
