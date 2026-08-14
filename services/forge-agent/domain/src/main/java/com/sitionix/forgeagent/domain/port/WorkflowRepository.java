package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.Workflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository {

    List<Workflow> findByProjectId(UUID projectId);

    Optional<Workflow> findById(UUID workflowId);

    Optional<Workflow> findByIdForUpdate(UUID workflowId);

    boolean existsByProjectIdAndNormalizedName(UUID projectId, String normalizedName);

    boolean existsByProjectIdAndNormalizedNameExcludingId(UUID projectId, String normalizedName, UUID excludedWorkflowId);

    Workflow save(Workflow workflow);

    void deleteById(UUID workflowId);
}
