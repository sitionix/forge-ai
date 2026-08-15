package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.util.ArrayList;
import java.util.HashMap;
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

    @Override
    public InputParticipation resolve(final UUID workflowRunId, final UUID activationFrameId, final UUID targetInputPortId) {
        final WorkflowRunGraph graph = this.graphRepository.findByWorkflowRunId(workflowRunId);
        final List<NodeRun> frameNodeRuns = this.nodeRunRepository.findByWorkflowRunIdAndExecutionFrameId(workflowRunId, activationFrameId);
        final List<ConnectionResolution> resolutions = this.resolutionRepository.findByWorkflowRunAndFrame(workflowRunId, activationFrameId);
        final ParticipationIndex index = new ParticipationIndex(graph, activationFrameId, frameNodeRuns, resolutions);
        final List<ConnectionResolution> delivered = new ArrayList<>();
        boolean open = false;
        for (final RunConnection incoming : this.graphRepository.findIncomingConnections(workflowRunId, targetInputPortId)) {
            final Optional<ConnectionResolution> resolution = index.resolutionForConnection(incoming.sourceConnectionId());
            if (resolution.filter(value -> value.type() == ConnectionResolutionType.DELIVERED).isPresent()) {
                delivered.add(resolution.orElseThrow());
            }
            if (resolution.isEmpty() && this.canStillContribute(incoming, index, new HashSet<>())) {
                open = true;
            }
        }
        return new InputParticipation(workflowRunId, activationFrameId, targetInputPortId, open, delivered);
    }

    private boolean canStillContribute(final RunConnection connection,
                                       final ParticipationIndex index,
                                       final Set<UUID> visitingNodes) {
        final UUID sourceNodeId = index.outputOwner(connection.sourceOutputPortId());
        final NodeRun sourceRun = index.nodeRun(sourceNodeId);
        if (sourceRun != null) {
            return !this.isTerminal(sourceRun.status()) || index.resolutionForConnection(connection.sourceConnectionId()).isEmpty();
        }
        if (!visitingNodes.add(sourceNodeId)) {
            return false;
        }
        final List<RunPort> inputPorts = index.inputPorts(sourceNodeId);
        if (inputPorts.isEmpty()) {
            return false;
        }
        for (final RunPort inputPort : inputPorts) {
            if (this.activationResolutionRepository.find(inputPort.workflowRunId(), index.frameId(), inputPort.sourcePortId()).isPresent()) {
                continue;
            }
            for (final RunConnection incoming : index.incoming(inputPort.sourcePortId())) {
                final Optional<ConnectionResolution> resolution = index.resolutionForConnection(incoming.sourceConnectionId());
                if (resolution.filter(value -> value.type() == ConnectionResolutionType.DELIVERED).isPresent()) {
                    return true;
                }
                if (resolution.isEmpty() && this.canStillContribute(incoming, index, visitingNodes)) {
                    return true;
                }
            }
        }
        return false;
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
        private final Map<UUID, NodeRun> nodeRunsBySourceNodeId;
        private final Map<UUID, ConnectionResolution> resolutionsByConnectionId;
        private final Map<UUID, UUID> outputOwners;
        private final Map<UUID, List<RunPort>> inputPortsByNode;
        private final Map<UUID, List<RunConnection>> incomingByInputPort;

        private ParticipationIndex(final WorkflowRunGraph graph,
                                   final UUID frameId,
                                   final List<NodeRun> nodeRuns,
                                   final List<ConnectionResolution> resolutions) {
            this.graph = graph;
            this.frameId = frameId;
            this.nodeRunsBySourceNodeId = nodeRuns.stream()
                    .collect(Collectors.toMap(NodeRun::sourceNodeId, nodeRun -> nodeRun, (left, right) -> left));
            this.resolutionsByConnectionId = resolutions.stream()
                    .collect(Collectors.toMap(ConnectionResolution::sourceConnectionId, resolution -> resolution, (left, right) -> left));
            this.outputOwners = graph.ports().stream()
                    .filter(port -> port.direction() == PortDirection.OUTPUT)
                    .collect(Collectors.toMap(RunPort::sourcePortId, RunPort::sourceNodeId));
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

        private NodeRun nodeRun(final UUID sourceNodeId) {
            return this.nodeRunsBySourceNodeId.get(sourceNodeId);
        }

        private List<RunPort> inputPorts(final UUID sourceNodeId) {
            return this.inputPortsByNode.getOrDefault(sourceNodeId, List.of());
        }

        private List<RunConnection> incoming(final UUID inputPortId) {
            return this.incomingByInputPort.getOrDefault(inputPortId, List.of());
        }

        private Optional<ConnectionResolution> resolutionForConnection(final UUID connectionId) {
            return Optional.ofNullable(this.resolutionsByConnectionId.get(connectionId));
        }
    }
}
