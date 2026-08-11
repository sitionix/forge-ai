package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataWorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {

    List<WorkflowRunEntity> findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(UUID sourceWorkflowId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowRunEntity w where w.id = :id")
    Optional<WorkflowRunEntity> findByIdForUpdate(@Param("id") UUID id);
}
