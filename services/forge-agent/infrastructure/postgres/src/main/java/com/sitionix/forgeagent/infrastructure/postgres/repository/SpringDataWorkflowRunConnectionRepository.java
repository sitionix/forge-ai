package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunConnectionEntityId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowRunConnectionRepository extends JpaRepository<WorkflowRunConnectionEntity, WorkflowRunConnectionEntityId> {

    List<WorkflowRunConnectionEntity> findByWorkflowRunIdOrderBySourceConnectionIdAsc(UUID workflowRunId);

    List<WorkflowRunConnectionEntity> findByWorkflowRunIdAndSourceOutputPortIdInOrderBySourceConnectionIdAsc(UUID workflowRunId, Collection<UUID> sourceOutputPortIds);

    List<WorkflowRunConnectionEntity> findByWorkflowRunIdAndTargetInputPortIdOrderBySourceConnectionIdAsc(UUID workflowRunId, UUID targetInputPortId);
}
