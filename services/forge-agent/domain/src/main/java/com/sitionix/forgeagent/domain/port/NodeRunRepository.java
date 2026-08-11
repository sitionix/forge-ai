package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.NodeRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NodeRunRepository {

    List<UUID> findPendingIds();

    Optional<UUID> findWorkflowRunIdById(UUID nodeRunId);

    Optional<NodeRun> findById(UUID nodeRunId);

    Optional<NodeRun> findByIdForUpdate(UUID nodeRunId);

    List<NodeRun> findByIds(Collection<UUID> nodeRunIds);

    List<NodeRun> findByWorkflowRunId(UUID workflowRunId);

    NodeRun save(NodeRun nodeRun);
}
