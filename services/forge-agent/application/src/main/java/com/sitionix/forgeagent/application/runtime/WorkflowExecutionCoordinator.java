package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
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
public class WorkflowExecutionCoordinator {

    private final WorkflowRunGraphRepository graphRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final NodeRunRepository nodeRunRepository;
    private final ConnectionResolutionRepository resolutionRepository;
    private final OutputRoutingPolicyRegistry outputRoutingPolicyRegistry;
    private final ConnectionResolutionProjector resolutionProjector;
    private final InputActivationPlanner inputActivationPlanner;
    private final WorkflowCompletionPolicy completionPolicy;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeSuccessfulNodeRun(final UUID nodeRunId) {
        final NodeRun nodeRun = this.nodeRunRepository.findByIdForUpdate(nodeRunId)
                .orElseThrow(() -> new ConflictException("NODE_RUN_NOT_FOUND", "Node run was not found."));
        if (nodeRun.status() != NodeRunStatus.SUCCEEDED) {
            throw new ConflictException("NODE_RUN_NOT_SUCCEEDED", "Only succeeded node runs can complete workflow routing.");
        }
        final WorkflowRun workflowRun = this.workflowRunRepository.findByIdForUpdate(nodeRun.workflowRunId())
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Owning workflow run was not found."));
        final List<RunPort> outputs = this.graphRepository.findOutputPortsByNode(workflowRun.id(), nodeRun.sourceNodeId());
        final List<RunConnection> outgoing = this.graphRepository.findConnectionsBySourceOutputPorts(
                workflowRun.id(),
                outputs.stream().map(RunPort::sourcePortId).toList()
        );
        this.outputRoutingPolicyRegistry.route(new OutputRoutingContext(nodeRun, nodeRun.output(), outputs, outgoing))
                .apply(new RoutingHandler(workflowRun, nodeRun, outgoing));
    }

    public WorkflowCompletionDecisionHandler completionDecisionHandler(final WorkflowRun workflowRun) {
        return new CompletionDecisionHandler(workflowRun);
    }

    public void reconcile(final WorkflowRun workflowRun) {
        this.completionPolicy.evaluate(workflowRun).apply(this.completionDecisionHandler(workflowRun));
    }

    private WorkflowRun withStatus(final WorkflowRun workflowRun, final WorkflowRunStatus status) {
        return new WorkflowRun(
                workflowRun.id(),
                workflowRun.projectId(),
                workflowRun.sourceWorkflowId(),
                workflowRun.taskId(),
                workflowRun.workflowName(),
                workflowRun.input(),
                status,
                workflowRun.nodeRuns(),
                workflowRun.connectionResolutions(),
                workflowRun.createdAt(),
                workflowRun.startedAt(),
                workflowRun.finishedAt() == null ? Instant.now(this.clock) : workflowRun.finishedAt()
        );
    }

    private final class CompletionDecisionHandler implements WorkflowCompletionDecisionHandler {
        private final WorkflowRun workflowRun;

        private CompletionDecisionHandler(final WorkflowRun workflowRun) {
            this.workflowRun = workflowRun;
        }

        @Override
        public void handle(final RunningWorkflowDecision decision) {
        }

        @Override
        public void handle(final SuccessfulWorkflowDecision decision) {
            WorkflowExecutionCoordinator.this.workflowRunRepository.saveLifecycle(
                    WorkflowExecutionCoordinator.this.withStatus(this.workflowRun, WorkflowRunStatus.SUCCEEDED)
            );
        }

        @Override
        public void handle(final FailedWorkflowDecision decision) {
            WorkflowExecutionCoordinator.this.workflowRunRepository.saveLifecycle(
                    WorkflowExecutionCoordinator.this.withStatus(this.workflowRun, WorkflowRunStatus.FAILED)
            );
        }
    }

    private void planActivations(final WorkflowRun workflowRun, final List<ConnectionResolution> resolutions) {
        this.inputActivationPlanner.planFromResolutions(workflowRun, resolutions);
        this.reconcile(workflowRun);
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
                nodeRun.status(),
                nodeRun.output(),
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt()
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
            WorkflowExecutionCoordinator.this.nodeRunRepository.save(
                    WorkflowExecutionCoordinator.this.withSelectedOutput(this.nodeRun, null)
            );
            WorkflowExecutionCoordinator.this.reconcile(this.workflowRun);
        }

        @Override
        public void handle(final SelectedOutputRoutingDecision decision) {
            final NodeRun selected = WorkflowExecutionCoordinator.this.nodeRunRepository.save(
                    WorkflowExecutionCoordinator.this.withSelectedOutput(this.nodeRun, decision.selectedOutputPortId())
            );
            final List<ConnectionResolution> resolutions = WorkflowExecutionCoordinator.this.resolutionProjector.selected(
                    selected,
                    selected.output(),
                    decision.selectedOutputPortId(),
                    this.outgoing
            );
            WorkflowExecutionCoordinator.this.resolutionRepository.saveAll(resolutions);
            WorkflowExecutionCoordinator.this.planActivations(this.workflowRun, resolutions);
        }
    }
}
