package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataNodeRunRepository extends JpaRepository<NodeRunEntity, UUID> {

    List<NodeRunEntity> findByWorkflowRunIdOrderByCreatedAtAscIdAsc(UUID workflowRunId);
}
