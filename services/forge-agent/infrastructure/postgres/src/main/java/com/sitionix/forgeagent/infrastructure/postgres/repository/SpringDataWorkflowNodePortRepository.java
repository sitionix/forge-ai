package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodePortEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowNodePortRepository extends JpaRepository<WorkflowNodePortEntity, UUID> {

    List<WorkflowNodePortEntity> findByWorkflowIdOrderByNodeIdAscPortOrderAsc(UUID workflowId);

    List<WorkflowNodePortEntity> findByWorkflowId(UUID workflowId);
}
