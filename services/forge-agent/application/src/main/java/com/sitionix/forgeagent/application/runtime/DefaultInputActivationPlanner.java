package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.InputActivationResolution;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultInputActivationPlanner implements InputActivationPlanner {

    private final WorkflowRunGraphRepository graphRepository;
    private final ExecutionFrameRepository frameRepository;
    private final NodeRunRepository nodeRunRepository;
    private final ConnectionResolutionRepository resolutionRepository;
    private final InputActivationResolutionRepository activationResolutionRepository;
    private final InputParticipationResolver participationResolver;
    private final InputResolutionEvaluator resolutionEvaluator;
    private final FrameTransitionPolicy frameTransitionPolicy;
    private final ScopeProjectionPolicy scopeProjectionPolicy;
    private final NodeRunFactory nodeRunFactory;
    private final ExecutionBudgetPolicy budgetPolicy;
    private final Clock clock;

    @Override
    public void planFromResolutions(final WorkflowRun workflowRun, final Collection<ConnectionResolution> resolutions) {
        final ArrayDeque<ActivationWorkItem> queue = new ArrayDeque<>();
        resolutions.stream()
                .map(resolution -> new ActivationWorkItem(resolution.executionFrameId(), resolution.targetInputPortId(), resolution.targetRepositoryId()))
                .distinct()
                .forEach(queue::add);
        this.drain(workflowRun, queue);
    }

    private void drain(final WorkflowRun workflowRun, final ArrayDeque<ActivationWorkItem> queue) {
        final Set<ActivationWorkItem> seen = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            final ActivationWorkItem item = queue.removeFirst();
            if (!seen.add(item)) {
                continue;
            }
            this.resolutionEvaluator
                    .evaluate(this.participationResolver.resolve(workflowRun.id(), item.activationFrameId(), item.targetInputPortId(), item.repositoryId()))
                    .apply(new PlannerActivationDecisionHandler(workflowRun, queue));
        }
    }

    private Set<UUID> downstreamInputs(final WorkflowRunGraph graph, final UUID closedInputPortId) {
        final UUID closedNodeId = graph.ports().stream()
                .filter(port -> port.sourcePortId().equals(closedInputPortId))
                .map(RunPort::sourceNodeId)
                .findFirst()
                .orElse(null);
        if (closedNodeId == null) {
            return Set.of();
        }
        final Set<UUID> outputPortIds = graph.ports().stream()
                .filter(port -> port.direction() == PortDirection.OUTPUT)
                .filter(port -> port.sourceNodeId().equals(closedNodeId))
                .map(RunPort::sourcePortId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return graph.connections().stream()
                .filter(connection -> outputPortIds.contains(connection.sourceOutputPortId()))
                .map(RunConnection::targetInputPortId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private record ActivationWorkItem(UUID activationFrameId, UUID targetInputPortId, UUID repositoryId) {
    }

    private final class PlannerActivationDecisionHandler implements ActivationDecisionHandler {
        private final WorkflowRun workflowRun;
        private final ArrayDeque<ActivationWorkItem> queue;

        private PlannerActivationDecisionHandler(final WorkflowRun workflowRun, final ArrayDeque<ActivationWorkItem> queue) {
            this.workflowRun = workflowRun;
            this.queue = queue;
        }

        @Override
        public void handle(final WaitActivationDecision decision) {
        }

        @Override
        public void handle(final CloseActivationDecision decision) {
            DefaultInputActivationPlanner.this.frameRepository.findByIdForUpdate(decision.activationFrameId())
                    .orElseThrow(() -> new ConflictException("EXECUTION_FRAME_NOT_FOUND", "Execution frame was not found."));
            DefaultInputActivationPlanner.this.activationResolutionRepository.find(
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId(),
                    decision.repositoryId()
            ).orElseGet(() -> DefaultInputActivationPlanner.this.activationResolutionRepository.save(new InputActivationResolution(
                    UUID.randomUUID(),
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId(),
                    null,
                    Instant.now(DefaultInputActivationPlanner.this.clock),
                    decision.repositoryId()
            )));
            DefaultInputActivationPlanner.this.downstreamInputs(
                    DefaultInputActivationPlanner.this.graphRepository.findByWorkflowRunId(decision.workflowRunId()),
                    decision.targetInputPortId()
            ).forEach(portId -> DefaultInputActivationPlanner.this.targetRepositories(
                            workflowRun,
                            decision.targetInputPortId(),
                            portId,
                            decision.repositoryId()
                    ).forEach(repositoryId -> this.queue.add(new ActivationWorkItem(decision.activationFrameId(), portId, repositoryId))));
        }

        @Override
        public void handle(final ActivateNodeDecision decision) {
            final ExecutionFrame activationFrame = DefaultInputActivationPlanner.this.frameRepository.findByIdForUpdate(decision.activationFrameId())
                    .orElseThrow(() -> new ConflictException("EXECUTION_FRAME_NOT_FOUND", "Execution frame was not found."));
            final java.util.Optional<InputActivationResolution> alreadyResolved = DefaultInputActivationPlanner.this.activationResolutionRepository.find(
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId(),
                    decision.repositoryId()
            );
            if (alreadyResolved.isPresent()) {
                return;
            }
            final RunPort inputPort = DefaultInputActivationPlanner.this.graphRepository.findPort(decision.workflowRunId(), decision.targetInputPortId())
                    .orElseThrow(() -> new ConflictException("RUN_INPUT_PORT_NOT_FOUND", "Runtime input port was not found."));
            final RunNode targetNode = DefaultInputActivationPlanner.this.graphRepository.findNode(decision.workflowRunId(), inputPort.sourceNodeId())
                    .orElseThrow(() -> new ConflictException("RUN_NODE_NOT_FOUND", "Runtime node was not found."));
            DefaultInputActivationPlanner.this.budgetPolicy.assertNodeRunCanBeCreated(this.workflowRun);
            final ExecutionFrame executionFrame = DefaultInputActivationPlanner.this.frameTransitionPolicy.frameForActivation(
                    this.workflowRun, activationFrame, targetNode, decision.targetInputPortId(), decision.repositoryId());
            final com.sitionix.forgeagent.domain.model.NodeRun nodeRun = DefaultInputActivationPlanner.this.nodeRunRepository.saveAndFlush(
                    DefaultInputActivationPlanner.this.nodeRunFactory.activated(
                            this.workflowRun,
                            executionFrame,
                            activationFrame,
                            targetNode,
                            decision.targetInputPortId(),
                            decision.repositoryId()
                    )
            );
            final int consumed = DefaultInputActivationPlanner.this.resolutionRepository.markConsumed(
                    decision.delivered().stream().map(ConnectionResolution::id).collect(java.util.stream.Collectors.toList()),
                    nodeRun.id()
            );
            if (consumed != decision.delivered().size()) {
                throw new ConflictException("INPUT_CONTRIBUTION_CONSUMPTION_CONFLICT", "Input contributions could not be atomically consumed.");
            }
            DefaultInputActivationPlanner.this.activationResolutionRepository.save(new InputActivationResolution(
                    UUID.randomUUID(),
                    decision.workflowRunId(),
                    decision.activationFrameId(),
                    decision.targetInputPortId(),
                    nodeRun.id(),
                    Instant.now(DefaultInputActivationPlanner.this.clock),
                    decision.repositoryId()
            ));
        }
    }

    private List<UUID> targetRepositories(final WorkflowRun workflowRun, final UUID sourceInputPortId,
                                          final UUID targetInputPortId, final UUID sourceRepositoryId) {
        final WorkflowRunGraph graph = this.graphRepository.findByWorkflowRunId(workflowRun.id());
        final RunPort sourceInputPort = graph.ports().stream()
                .filter(port -> port.sourcePortId().equals(sourceInputPortId))
                .findFirst()
                .orElseThrow(() -> new ConflictException("RUN_PORT_NOT_FOUND", "Runtime source input port was not found."));
        final RunNode sourceNode = graph.nodes().stream()
                .filter(node -> node.sourceNodeId().equals(sourceInputPort.sourceNodeId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("RUN_NODE_NOT_FOUND", "Runtime source node was not found."));
        final RunPort targetInputPort = graph.ports().stream()
                .filter(port -> port.sourcePortId().equals(targetInputPortId))
                .findFirst()
                .orElseThrow(() -> new ConflictException("RUN_PORT_NOT_FOUND", "Runtime target input port was not found."));
        final RunNode targetNode = graph.nodes().stream()
                .filter(node -> node.sourceNodeId().equals(targetInputPort.sourceNodeId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("RUN_NODE_NOT_FOUND", "Runtime target node was not found."));
        return this.scopeProjectionPolicy.project(
                sourceNode.scopeMode(),
                targetNode.scopeMode(),
                sourceRepositoryId,
                workflowRun.repositoryIds()
        );
    }
}
