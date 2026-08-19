package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataWorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {

    @Override
    @EntityGraph(attributePaths = "repositoryIds")
    Optional<WorkflowRunEntity> findById(UUID id);

    List<WorkflowRunEntity> findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(UUID sourceWorkflowId);

    List<WorkflowRunEntity> findByTaskIdOrderByCreatedAtDescIdDesc(UUID taskId);

    boolean existsByProjectIdAndStatusIn(UUID projectId, List<String> statuses);

    boolean existsByTaskIdAndStatusIn(UUID taskId, List<String> statuses);

    boolean existsBySourceWorkflowIdAndStatusIn(UUID sourceWorkflowId, List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select distinct w from WorkflowRunEntity w left join fetch w.repositoryIds where w.id = :id")
    Optional<WorkflowRunEntity> findByIdForUpdate(@Param("id") UUID id);
}
