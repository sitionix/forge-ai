package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SpringDataNodeRunRepository extends JpaRepository<NodeRunEntity, UUID> {

    List<NodeRunEntity> findByWorkflowRunIdOrderByCreatedAtAscIdAsc(UUID workflowRunId);

    @Query("""
            select n.id
            from NodeRunEntity n
            join WorkflowRunEntity w on w.id = n.workflowRunId
            where n.status = 'PENDING'
              and w.status in ('QUEUED', 'RUNNING')
            order by n.createdAt asc, n.id asc
            """)
    List<UUID> findPendingIds();

    @Query("select n.workflowRunId from NodeRunEntity n where n.id = :id")
    Optional<UUID> findWorkflowRunIdById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NodeRunEntity n where n.id = :id")
    Optional<NodeRunEntity> findByIdForUpdate(@Param("id") UUID id);
}
