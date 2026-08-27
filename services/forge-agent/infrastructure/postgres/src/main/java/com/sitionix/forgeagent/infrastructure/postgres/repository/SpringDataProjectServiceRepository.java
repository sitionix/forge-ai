package com.sitionix.forgeagent.infrastructure.postgres.repository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectServiceEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SpringDataProjectServiceRepository extends JpaRepository<ProjectServiceEntity,UUID> {
 List<ProjectServiceEntity> findAllByProjectIdOrderByNameAscIdAsc(UUID projectId);
}
