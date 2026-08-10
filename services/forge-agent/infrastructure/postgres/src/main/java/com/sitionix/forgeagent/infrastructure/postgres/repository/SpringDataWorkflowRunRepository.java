package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {

    List<WorkflowRunEntity> findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(UUID sourceWorkflowId);
}
