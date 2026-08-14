package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntityId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowNodeRepository extends JpaRepository<WorkflowNodeEntity, WorkflowNodeEntityId> {

    List<WorkflowNodeEntity> findByWorkflowIdOrderByIdAsc(UUID workflowId);

    List<WorkflowNodeEntity> findByWorkflowId(UUID workflowId);

    boolean existsByTargetId(UUID targetId);
}
