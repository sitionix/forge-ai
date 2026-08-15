package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunGraphRepository {

    void saveSnapshot(WorkflowRunGraph graph);

    WorkflowRunGraph findByWorkflowRunId(UUID workflowRunId);

    Optional<RunNode> findNode(UUID workflowRunId, UUID sourceNodeId);

    Optional<RunPort> findPort(UUID workflowRunId, UUID sourcePortId);

    List<RunPort> findOutputPortsByNode(UUID workflowRunId, UUID sourceNodeId);

    List<RunConnection> findConnectionsBySourceOutputPorts(UUID workflowRunId, Collection<UUID> sourceOutputPortIds);

    List<RunConnection> findIncomingConnections(UUID workflowRunId, UUID targetInputPortId);
}
