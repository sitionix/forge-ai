package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NodeRunCompletionApplier {

    private final WorkflowRunRepository workflowRunRepository;
    private final NodeRunRepository nodeRunRepository;
    private final ConnectionResolutionRepository resolutionRepository;
    private final ConnectionResolutionProjector resolutionProjector;
    private final InputActivationPlanner inputActivationPlanner;
    private final WorkflowExecutionCoordinator coordinator;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(final UUID nodeRunId, final OutputRoutingDecision decision, final List<RunConnection> outgoing) {
        final UUID workflowRunId = this.nodeRunRepository.findWorkflowRunIdById(nodeRunId)
                .orElseThrow(() -> new ConflictException("NODE_RUN_NOT_FOUND", "Node run was not found."));
        final WorkflowRun workflowRun = this.workflowRunRepository.findByIdForUpdate(workflowRunId)
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Owning workflow run was not found."));
        final NodeRun nodeRun = this.nodeRunRepository.findByIdForUpdate(nodeRunId)
                .orElseThrow(() -> new ConflictException("NODE_RUN_NOT_FOUND", "Node run was not found."));
        if (nodeRun.routingCompletedAt() != null || nodeRun.status() != NodeRunStatus.SUCCEEDED) {
            return;
        }
        if (this.isTerminal(workflowRun.status())) {
            return;
        }
        decision.apply(new RoutingHandler(workflowRun, nodeRun, outgoing));
    }

    private boolean isTerminal(final WorkflowRunStatus status) {
        return status == WorkflowRunStatus.SUCCEEDED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELLED;
    }

    private NodeRun withRoutingCompleted(final NodeRun nodeRun, final UUID selectedOutputPortId) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.inputMode(),
                nodeRun.position(),
                nodeRun.executionFrameId(),
                nodeRun.enteredViaInputPortId(),
                nodeRun.activationFrameId(),
                selectedOutputPortId,
                Instant.now(this.clock),
                nodeRun.status(),
                nodeRun.output(),
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt(),
                nodeRun.repositoryId()
        );
    }

    private NodeRun withSelectedOutput(final NodeRun nodeRun, final UUID selectedOutputPortId) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.inputMode(),
                nodeRun.position(),
                nodeRun.executionFrameId(),
                nodeRun.enteredViaInputPortId(),
                nodeRun.activationFrameId(),
                selectedOutputPortId,
                nodeRun.routingCompletedAt(),
                nodeRun.status(),
                nodeRun.output(),
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt(),
                nodeRun.repositoryId()
        );
    }

    private final class RoutingHandler implements OutputRoutingDecisionHandler {
        private final WorkflowRun workflowRun;
        private final NodeRun nodeRun;
        private final List<RunConnection> outgoing;

        private RoutingHandler(final WorkflowRun workflowRun, final NodeRun nodeRun, final List<RunConnection> outgoing) {
            this.workflowRun = workflowRun;
            this.nodeRun = nodeRun;
            this.outgoing = outgoing;
        }

        @Override
        public void handle(final TerminalRoutingDecision decision) {
            NodeRunCompletionApplier.this.nodeRunRepository.saveAndFlush(
                    NodeRunCompletionApplier.this.withRoutingCompleted(this.nodeRun, null)
            );
            NodeRunCompletionApplier.this.coordinator.reconcile(this.workflowRun);
        }

        @Override
        public void handle(final SelectedOutputRoutingDecision decision) {
            final NodeRun selected = NodeRunCompletionApplier.this.nodeRunRepository.save(
                    NodeRunCompletionApplier.this.withSelectedOutput(this.nodeRun, decision.selectedOutputPortId())
            );
            final List<ConnectionResolution> resolutions = NodeRunCompletionApplier.this.resolutionProjector.selected(
                    this.workflowRun,
                    selected,
                    selected.output(),
                    decision.selectedOutputPortId(),
                    this.outgoing
            );
            NodeRunCompletionApplier.this.resolutionRepository.saveAll(resolutions);
            NodeRunCompletionApplier.this.inputActivationPlanner.planFromResolutions(this.workflowRun, resolutions);
            NodeRunCompletionApplier.this.nodeRunRepository.saveAndFlush(
                    NodeRunCompletionApplier.this.withRoutingCompleted(selected, decision.selectedOutputPortId())
            );
            NodeRunCompletionApplier.this.coordinator.reconcile(this.workflowRun);
        }
    }
}
