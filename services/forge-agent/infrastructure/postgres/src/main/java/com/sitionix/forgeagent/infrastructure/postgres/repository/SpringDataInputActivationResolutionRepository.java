package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.InputActivationResolutionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataInputActivationResolutionRepository extends JpaRepository<InputActivationResolutionEntity, UUID> {

    Optional<InputActivationResolutionEntity> findByWorkflowRunIdAndActivationFrameIdAndTargetInputPortId(
            UUID workflowRunId,
            UUID activationFrameId,
            UUID targetInputPortId
    );
}
