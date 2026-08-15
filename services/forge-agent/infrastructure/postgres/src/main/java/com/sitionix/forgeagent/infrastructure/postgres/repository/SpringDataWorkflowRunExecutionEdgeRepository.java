package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunExecutionEdgeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunExecutionEdgeEntityId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowRunExecutionEdgeRepository extends JpaRepository<WorkflowRunExecutionEdgeEntity, WorkflowRunExecutionEdgeEntityId> {

    List<WorkflowRunExecutionEdgeEntity> findByWorkflowRunIdOrderBySourceNodeRunIdAscTargetNodeRunIdAsc(UUID workflowRunId);
}
