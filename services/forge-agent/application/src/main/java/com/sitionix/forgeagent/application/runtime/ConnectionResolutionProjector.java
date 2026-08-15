package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.RunConnection;
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

    public List<ConnectionResolution> terminal(final NodeRun nodeRun) {
        return List.of();
    }

    public List<ConnectionResolution> selected(final NodeRun nodeRun,
                                               final NodeRunOutput output,
                                               final UUID selectedOutputPortId,
                                               final List<RunConnection> outgoingConnections) {
        final Instant now = Instant.now(this.clock);
        return outgoingConnections.stream()
                .map(connection -> this.resolution(nodeRun, output, selectedOutputPortId, connection, now))
                .toList();
    }

    private ConnectionResolution resolution(final NodeRun nodeRun,
                                            final NodeRunOutput output,
                                            final UUID selectedOutputPortId,
                                            final RunConnection connection,
                                            final Instant now) {
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
                now
        );
    }
}
