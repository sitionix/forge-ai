package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GraphStateInputParticipationResolver implements InputParticipationResolver {

    private final WorkflowRunGraphRepository graphRepository;
    private final NodeRunRepository nodeRunRepository;
    private final ConnectionResolutionRepository resolutionRepository;
    private final InputActivationResolutionRepository activationResolutionRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ScopeProjectionPolicy scopeProjectionPolicy;

    @Override
    public InputParticipation resolve(final UUID workflowRunId, final UUID activationFrameId,
                                      final UUID targetInputPortId, final UUID repositoryId) {
        final WorkflowRunGraph graph = this.graphRepository.findByWorkflowRunId(workflowRunId);
        final WorkflowRun workflowRun = this.workflowRunRepository.findById(workflowRunId)
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Workflow run runtime state was not found."));
        final List<NodeRun> frameNodeRuns = this.nodeRunRepository.findByWorkflowRunIdAndExecutionFrameId(workflowRunId, activationFrameId);
        final List<ConnectionResolution> resolutions = this.resolutionRepository.findByWorkflowRunAndFrame(workflowRunId, activationFrameId);
        final ParticipationIndex index = new ParticipationIndex(graph, activationFrameId, frameNodeRuns, resolutions);
        final List<ConnectionResolution> delivered = new ArrayList<>();
        boolean open = false;
        for (final RunConnection incoming : this.graphRepository.findIncomingConnections(workflowRunId, targetInputPortId)) {
            final UUID sourceNodeId = index.outputOwner(incoming.sourceOutputPortId());
            final RunNode sourceNode = graph.nodes().stream().filter(node -> node.sourceNodeId().equals(sourceNodeId))
                    .findFirst().orElseThrow(() -> new ConflictException("RUN_NODE_NOT_FOUND", "Runtime source node was not found."));
            final RunNode targetNode = index.inputOwner(targetInputPortId);
            for (final UUID sourceRepositoryId : this.expectedSourceRepositories(sourceNode, targetNode, repositoryId, workflowRun.repositoryIds())) {
                final Optional<ConnectionResolution> resolution = index.resolutionForConnection(incoming.sourceConnectionId(), repositoryId, sourceRepositoryId);
                resolution.filter(value -> value.type() == ConnectionResolutionType.DELIVERED).ifPresent(delivered::add);
                if (resolution.isEmpty() && this.canStillContribute(incoming, index, workflowRun, repositoryId,
                        sourceRepositoryId, new HashSet<>())) {
                    open = true;
                }
            }
        }
        return new InputParticipation(workflowRunId, activationFrameId, targetInputPortId, open, delivered, repositoryId);
    }

    private boolean canStillContribute(final RunConnection connection,
                                       final ParticipationIndex index,
                                       final WorkflowRun workflowRun,
                                       final UUID targetRepositoryId,
                                       final UUID sourceRepositoryId,
                                       final Set<NodeScopeKey> visitingNodes) {
        final UUID sourceNodeId = index.outputOwner(connection.sourceOutputPortId());
        final NodeRun sourceRun = index.nodeRun(sourceNodeId, sourceRepositoryId);
        if (sourceRun != null) {
            return !this.isTerminal(sourceRun.status())
                    || index.resolutionForConnection(connection.sourceConnectionId(), targetRepositoryId, sourceRepositoryId).isEmpty();
        }
        if (!visitingNodes.add(new NodeScopeKey(sourceNodeId, sourceRepositoryId))) {
            return false;
        }
        final List<RunPort> inputPorts = index.inputPorts(sourceNodeId);
        if (inputPorts.isEmpty()) {
            return false;
        }
        for (final RunPort inputPort : inputPorts) {
            if (this.activationResolutionRepository.find(inputPort.workflowRunId(), index.frameId(), inputPort.sourcePortId(), sourceRepositoryId).isPresent()) {
                continue;
            }
            for (final RunConnection incoming : index.incoming(inputPort.sourcePortId())) {
                final UUID upstreamNodeId = index.outputOwner(incoming.sourceOutputPortId());
                final RunNode upstreamNode = index.node(upstreamNodeId);
                final RunNode currentNode = index.node(sourceNodeId);
                for (final UUID upstreamRepositoryId : this.expectedSourceRepositories(
                        upstreamNode,
                        currentNode,
                        sourceRepositoryId,
                        workflowRun.repositoryIds()
                )) {
                    final Optional<ConnectionResolution> resolution = index.resolutionForConnection(
                            incoming.sourceConnectionId(),
                            sourceRepositoryId,
                            upstreamRepositoryId
                    );
                    if (resolution.filter(value -> value.type() == ConnectionResolutionType.DELIVERED).isPresent()) {
                        return true;
                    }
                    if (resolution.isEmpty() && this.canStillContribute(incoming, index, workflowRun,
                            sourceRepositoryId, upstreamRepositoryId, visitingNodes)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<UUID> expectedSourceRepositories(final RunNode sourceNode, final RunNode targetNode,
                                                  final UUID targetRepositoryId, final List<UUID> repositoryIds) {
        final List<UUID> candidates = this.scopeProjectionPolicy.invocationRepositories(sourceNode.scopeMode(), repositoryIds);
        return candidates.stream()
                .filter(sourceRepositoryId -> this.scopeProjectionPolicy.project(
                        sourceNode.scopeMode(),
                        targetNode.scopeMode(),
                        sourceRepositoryId,
                        repositoryIds
                ).contains(targetRepositoryId))
                .toList();
    }

    private boolean isTerminal(final NodeRunStatus status) {
        return status == NodeRunStatus.SUCCEEDED
                || status == NodeRunStatus.FAILED
                || status == NodeRunStatus.BLOCKED
                || status == NodeRunStatus.CANCELLED;
    }

    private static final class ParticipationIndex {
        private final WorkflowRunGraph graph;
        private final UUID frameId;
        private final List<NodeRun> nodeRuns;
        private final List<ConnectionResolution> resolutions;
        private final Map<UUID, UUID> outputOwners;
        private final Map<UUID, RunNode> nodesById;
        private final Map<UUID, RunNode> inputOwners;
        private final Map<UUID, List<RunPort>> inputPortsByNode;
        private final Map<UUID, List<RunConnection>> incomingByInputPort;

        private ParticipationIndex(final WorkflowRunGraph graph,
                                   final UUID frameId,
                                   final List<NodeRun> nodeRuns,
                                   final List<ConnectionResolution> resolutions) {
            this.graph = graph;
            this.frameId = frameId;
            this.nodeRuns = nodeRuns;
            this.resolutions = resolutions;
            this.outputOwners = graph.ports().stream()
                    .filter(port -> port.direction() == PortDirection.OUTPUT)
                    .collect(Collectors.toMap(RunPort::sourcePortId, RunPort::sourceNodeId));
            this.nodesById = graph.nodes().stream()
                    .collect(Collectors.toMap(RunNode::sourceNodeId, java.util.function.Function.identity()));
            this.inputOwners = graph.ports().stream()
                    .filter(port -> port.direction() == PortDirection.INPUT)
                    .collect(Collectors.toMap(RunPort::sourcePortId, port -> this.nodesById.get(port.sourceNodeId())));
            this.inputPortsByNode = graph.ports().stream()
                    .filter(port -> port.direction() == PortDirection.INPUT)
                    .collect(Collectors.groupingBy(RunPort::sourceNodeId));
            this.incomingByInputPort = graph.connections().stream()
                    .collect(Collectors.groupingBy(RunConnection::targetInputPortId));
        }

        private UUID frameId() {
            return this.frameId;
        }

        private UUID outputOwner(final UUID outputPortId) {
            return this.outputOwners.get(outputPortId);
        }

        private RunNode inputOwner(final UUID inputPortId) {
            return this.inputOwners.get(inputPortId);
        }

        private RunNode node(final UUID sourceNodeId) {
            return this.nodesById.get(sourceNodeId);
        }

        private NodeRun nodeRun(final UUID sourceNodeId, final UUID repositoryId) {
            return this.nodeRuns.stream().filter(run -> run.sourceNodeId().equals(sourceNodeId))
                    .filter(run -> java.util.Objects.equals(run.repositoryId(), repositoryId)).findFirst().orElse(null);
        }

        private List<RunPort> inputPorts(final UUID sourceNodeId) {
            return this.inputPortsByNode.getOrDefault(sourceNodeId, List.of());
        }

        private List<RunConnection> incoming(final UUID inputPortId) {
            return this.incomingByInputPort.getOrDefault(inputPortId, List.of());
        }

        private Optional<ConnectionResolution> resolutionForConnection(final UUID connectionId,
                                                                        final UUID targetRepositoryId,
                                                                        final UUID sourceRepositoryId) {
            return this.resolutions.stream().filter(resolution -> resolution.sourceConnectionId().equals(connectionId))
                    .filter(resolution -> java.util.Objects.equals(resolution.targetRepositoryId(), targetRepositoryId))
                    .filter(resolution -> this.nodeRuns.stream().anyMatch(run -> run.id().equals(resolution.sourceNodeRunId())
                            && java.util.Objects.equals(run.repositoryId(), sourceRepositoryId)))
                    .findFirst();
        }
    }

    private record NodeScopeKey(UUID sourceNodeId, UUID repositoryId) {
    }
}
