package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunNodeEntityId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowRunNodeRepository extends JpaRepository<WorkflowRunNodeEntity, WorkflowRunNodeEntityId> {

    List<WorkflowRunNodeEntity> findByWorkflowRunIdOrderBySourceNodeIdAsc(UUID workflowRunId);
}
