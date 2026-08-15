package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunPortEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunPortEntityId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowRunPortRepository extends JpaRepository<WorkflowRunPortEntity, WorkflowRunPortEntityId> {

    List<WorkflowRunPortEntity> findByWorkflowRunIdOrderBySourceNodeIdAscPortOrderAsc(UUID workflowRunId);

    List<WorkflowRunPortEntity> findByWorkflowRunIdAndSourceNodeIdAndDirectionOrderByPortOrderAsc(UUID workflowRunId, UUID sourceNodeId, String direction);
}
