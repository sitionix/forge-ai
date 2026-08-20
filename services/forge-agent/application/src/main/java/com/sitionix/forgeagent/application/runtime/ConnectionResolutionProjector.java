package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConnectionResolutionProjector {

    private final Clock clock;
    private final WorkflowRunGraphRepository graphRepository;
    private final ScopeProjectionPolicy scopeProjectionPolicy;

    public List<ConnectionResolution> terminal(final NodeRun nodeRun) {
        return List.of();
    }

    public List<ConnectionResolution> selected(final WorkflowRun workflowRun, final NodeRun nodeRun,
                                               final NodeRunOutput output,
                                               final UUID selectedOutputPortId,
                                               final List<RunConnection> outgoingConnections) {
        final Instant now = Instant.now(this.clock);
        final RunNode sourceNode = this.graphRepository.findNode(nodeRun.workflowRunId(), nodeRun.sourceNodeId())
                .orElseThrow(() -> new ConflictException("RUN_NODE_NOT_FOUND", "Runtime source node was not found."));
        return outgoingConnections.stream().flatMap(connection -> {
            final RunPort targetPort = this.graphRepository.findPort(nodeRun.workflowRunId(), connection.targetInputPortId())
                    .orElseThrow(() -> new ConflictException("RUN_PORT_NOT_FOUND", "Runtime target input port was not found."));
            final RunNode targetNode = this.graphRepository.findNode(nodeRun.workflowRunId(), targetPort.sourceNodeId())
                    .orElseThrow(() -> new ConflictException("RUN_NODE_NOT_FOUND", "Runtime target node was not found."));
            return this.scopeProjectionPolicy.project(sourceNode.scopeMode(), targetNode.scopeMode(), nodeRun.repositoryId(), workflowRun.repositoryIds())
                    .stream().map(repositoryId -> this.resolution(nodeRun, output, selectedOutputPortId, connection, now, repositoryId));
        })
                .toList();
    }

    private ConnectionResolution resolution(final NodeRun nodeRun,
                                            final NodeRunOutput output,
                                            final UUID selectedOutputPortId,
                                            final RunConnection connection,
                                            final Instant now, final UUID targetRepositoryId) {
        final boolean delivered = selectedOutputPortId.equals(connection.sourceOutputPortId());
        return new ConnectionResolution(
                UUID.randomUUID(),
                nodeRun.workflowRunId(),
                nodeRun.executionFrameId(),
                nodeRun.id(),
                connection.sourceConnectionId(),
                connection.targetInputPortId(),
                delivered ? ConnectionResolutionType.DELIVERED : ConnectionResolutionType.CLOSED,
                delivered ? output : null,
                null,
                now,
                targetRepositoryId
        );
    }
}
