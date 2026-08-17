package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectRepositoryLinkRepository extends JpaRepository<ProjectRepositoryEntity, UUID> {

    List<ProjectRepositoryEntity> findByProjectIdOrderByCreatedAtAscIdAsc(UUID projectId);
}
