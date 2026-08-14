package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectTaskRepository extends JpaRepository<ProjectTaskEntity, UUID> {

    Page<ProjectTaskEntity> findByProjectId(UUID projectId, Pageable pageable);

    boolean existsByWorkflowId(UUID workflowId);
}
