package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataWorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {

    List<WorkflowEntity> findByProjectIdOrderByNormalizedNameAscIdAsc(UUID projectId);

    boolean existsByProjectIdAndNormalizedName(UUID projectId, String normalizedName);

    boolean existsByProjectIdAndNormalizedNameAndIdNot(UUID projectId, String normalizedName, UUID excludedWorkflowId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workflow from WorkflowEntity workflow where workflow.id = :workflowId")
    Optional<WorkflowEntity> findByIdForUpdate(@Param("workflowId") UUID workflowId);
}
