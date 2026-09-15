package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataNodeRunRepository extends JpaRepository<NodeRunEntity, UUID> {

    List<NodeRunEntity> findByWorkflowRunIdOrderByCreatedAtAscIdAsc(UUID workflowRunId);

    List<NodeRunEntity> findByWorkflowRunIdAndExecutionFrameIdOrderByCreatedAtAscIdAsc(UUID workflowRunId, UUID executionFrameId);

    @Query("""
            select n.id
            from NodeRunEntity n
            join WorkflowRunEntity w on w.id = n.workflowRunId
            where n.status = 'PENDING'
              and n.executionFrameId is not null
              and w.status in ('QUEUED', 'RUNNING')
            order by n.createdAt asc, n.id asc
            """)
    List<UUID> findPendingIds();

    @Query("""
            select n.id
            from NodeRunEntity n
            join WorkflowRunEntity w on w.id = n.workflowRunId
            where n.status = 'SUCCEEDED'
              and n.routingCompletedAt is null
              and n.executionFrameId is not null
              and w.status in ('QUEUED', 'RUNNING')
            order by n.createdAt asc, n.id asc
            """)
    List<UUID> findSuccessfulUnroutedIds();

    @Query("""
            select distinct n.workflowRunId
            from NodeRunEntity n
            join WorkflowRunEntity w on w.id = n.workflowRunId
            where n.status in ('FAILED', 'BLOCKED')
              and w.status in ('QUEUED', 'RUNNING')
              and w.finishedAt is null
            order by n.workflowRunId
            """)
    List<UUID> findWorkflowRunIdsRequiringCompletion(Pageable pageable);

    @Query("select n.workflowRunId from NodeRunEntity n where n.id = :id")
    Optional<UUID> findWorkflowRunIdById(@Param("id") UUID id);

    boolean existsBySourceAgentIdAndStatusIn(UUID sourceAgentId, List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NodeRunEntity n where n.id = :id")
    Optional<NodeRunEntity> findByIdForUpdate(@Param("id") UUID id);
}
