package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectTaskRepository extends JpaRepository<ProjectTaskEntity, UUID> {

    List<ProjectTaskEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);
}
