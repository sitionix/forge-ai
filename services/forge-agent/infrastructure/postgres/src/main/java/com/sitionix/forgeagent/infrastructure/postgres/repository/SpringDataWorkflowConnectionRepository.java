package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowConnectionEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowConnectionRepository extends JpaRepository<WorkflowConnectionEntity, UUID> {

    List<WorkflowConnectionEntity> findBySourceOutputPortIdIn(Collection<UUID> sourceOutputPortIds);
}
