package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.LogSourceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataLogSourceRepository extends JpaRepository<LogSourceEntity, UUID> {
    List<LogSourceEntity> findAllByProjectIdOrderByNameAscIdAsc(UUID projectId);
    List<LogSourceEntity> findAllByProjectIdAndServiceIdOrderByNameAscIdAsc(UUID projectId, UUID serviceId);
}
