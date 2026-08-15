package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ConnectionResolutionEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataConnectionResolutionRepository extends JpaRepository<ConnectionResolutionEntity, UUID> {

    List<ConnectionResolutionEntity> findByWorkflowRunIdAndExecutionFrameIdOrderByCreatedAtAscIdAsc(UUID workflowRunId, UUID executionFrameId);

    List<ConnectionResolutionEntity> findByWorkflowRunIdOrderByCreatedAtAscIdAsc(UUID workflowRunId);

    List<ConnectionResolutionEntity> findBySourceNodeRunIdOrderByCreatedAtAscIdAsc(UUID sourceNodeRunId);

    List<ConnectionResolutionEntity> findByConsumedByNodeRunIdOrderByCreatedAtAscIdAsc(UUID consumedByNodeRunId);

    @Modifying
    @Query("""
            update ConnectionResolutionEntity r
            set r.consumedByNodeRunId = :nodeRunId
            where r.id in :ids
              and r.consumedByNodeRunId is null
            """)
    int markConsumed(@Param("ids") Collection<UUID> ids, @Param("nodeRunId") UUID nodeRunId);
}
