package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectAssetEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectAssetRepository extends JpaRepository<ProjectAssetEntity, UUID> {
  List<ProjectAssetEntity> findByProjectIdOrderByCreatedAtAscIdAsc(UUID projectId);
}
