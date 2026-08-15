package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.InputActivationResolution;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowExecutionCoordinator {

    private final WorkflowRunGraphRepository graphRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final NodeRunRepository nodeRunRepository;
    private final ExecutionFrameRepository frameRepository;
    private final ConnectionResolutionRepository resolutionRepository;
    private final InputActivationResolutionRepository activationResolutionRepository;
    private final OutputRoutingPolicyRegistry outputRoutingPolicyRegistry;
    private final ConnectionResolutionProjector resolutionProjector;
    private final InputParticipationResolver inputParticipationResolver;
    private final InputResolutionEvaluator inputResolutionEvaluator;
    private final FrameTransitionPolicy frameTransitionPolicy;
    private final NodeRunFactory nodeRunFactory;
    private final WorkflowCompletionPolicy completionPolicy;
    private final ExecutionBudgetPolicy budgetPolicy;
    private final Clock clock;

    @Transactional
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

    private void planActivations(final WorkflowRun workflowRun, final Collection<ConnectionResolution> resolutions) {
        resolutions.stream()
                .map(ConnectionResolution::targetInputPortId)
                .distinct()
                .forEach(targetInputPortId -> this.inputResolutionEvaluator
                        .evaluate(this.inputParticipationResolver.resolve(workflowRun.id(), resolutions.iterator().next().executionFrameId(), targetInputPortId))
                        .apply(new InputActivationHandler(workflowRun)));
        this.reconcile(workflowRun);
    }

    private void reconcile(final WorkflowRun workflowRun) {
        final WorkflowCompletionDecision decision = this.completionPolicy.evaluate(workflowRun);
        if (!decision.terminal()) {
            return;
        }
        this.workflowRunRepository.saveLifecycle(new WorkflowRun(
                workflowRun.id(),
                workflowRun.projectId(),
                workflowRun.sourceWorkflowId(),
                workflowRun.taskId(),
                workflowRun.workflowName(),
                workflowRun.input(),
                decision.status(),
                workflowRun.nodeRuns(),
                workflowRun.createdAt(),
                workflowRun.startedAt(),
                workflowRun.finishedAt() == null ? Instant.now(this.clock) : workflowRun.finishedAt()
        ));
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

    private final class InputActivationHandler implements ActivationDecisionHandler {
        private final WorkflowRun workflowRun;

        private InputActivationHandler(final WorkflowRun workflowRun) {
            this.workflowRun = workflowRun;
        }

        @Override
        public void handle(final WaitActivationDecision decision) {
        }

        @Override
        public void handle(final CloseActivationDecision decision) {
            WorkflowExecutionCoordinator.this.frameRepository.findByIdForUpdate(decision.activationFrameId())
                    .orElseThrow(() -> new ConflictException("EXECUTION_FRAME_NOT_FOUND", "Execution frame was not found."));
            WorkflowExecutionCoordinator.this.activationResolutionRepository.find(
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId()
            ).orElseGet(() -> WorkflowExecutionCoordinator.this.activationResolutionRepository.save(new InputActivationResolution(
                    UUID.randomUUID(),
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId(),
                    null,
                    Instant.now(WorkflowExecutionCoordinator.this.clock)
            )));
        }

        @Override
        public void handle(final ActivateNodeDecision decision) {
            final ExecutionFrame activationFrame = WorkflowExecutionCoordinator.this.frameRepository.findByIdForUpdate(decision.activationFrameId())
                    .orElseThrow(() -> new ConflictException("EXECUTION_FRAME_NOT_FOUND", "Execution frame was not found."));
            final RunPort inputPort = WorkflowExecutionCoordinator.this.graphRepository.findPort(decision.workflowRunId(), decision.targetInputPortId())
                    .orElseThrow(() -> new ConflictException("RUN_INPUT_PORT_NOT_FOUND", "Runtime input port was not found."));
            final RunNode targetNode = WorkflowExecutionCoordinator.this.graphRepository.findNode(decision.workflowRunId(), inputPort.sourceNodeId())
                    .orElseThrow(() -> new ConflictException("RUN_NODE_NOT_FOUND", "Runtime node was not found."));
            final NodeRun existing = WorkflowExecutionCoordinator.this.nodeRunRepository
                    .findByWorkflowRunIdAndActivationFrameIdAndEnteredViaInputPortId(decision.workflowRunId(), decision.activationFrameId(), decision.targetInputPortId())
                    .orElse(null);
            if (existing != null) {
                return;
            }
            WorkflowExecutionCoordinator.this.activationResolutionRepository.find(
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId()
            ).ifPresent(ignored -> {
                throw new ConflictException("INPUT_ACTIVATION_ALREADY_RESOLVED", "Input activation was already resolved.");
            });
            WorkflowExecutionCoordinator.this.budgetPolicy.assertNodeRunCanBeCreated(this.workflowRun);
            final ExecutionFrame executionFrame = WorkflowExecutionCoordinator.this.frameTransitionPolicy.frameForActivation(this.workflowRun, activationFrame, targetNode);
            final NodeRun nodeRun = WorkflowExecutionCoordinator.this.nodeRunRepository.save(
                    WorkflowExecutionCoordinator.this.nodeRunFactory.activated(
                            this.workflowRun,
                            executionFrame,
                            activationFrame,
                            targetNode,
                            decision.targetInputPortId()
                    )
            );
            WorkflowExecutionCoordinator.this.resolutionRepository.markConsumed(
                    decision.delivered().stream().map(ConnectionResolution::id).collect(Collectors.toList()),
                    nodeRun.id()
            );
            WorkflowExecutionCoordinator.this.activationResolutionRepository.save(new InputActivationResolution(
                    UUID.randomUUID(),
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId(),
                    nodeRun.id(),
                    Instant.now(WorkflowExecutionCoordinator.this.clock)
            ));
        }
    }
}
