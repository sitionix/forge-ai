package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ProjectTask;
import com.sitionix.forgeagent.domain.model.ProjectTaskPage;
import java.util.Optional;
import java.util.UUID;

public interface ProjectTaskRepository {

    ProjectTask save(ProjectTask task);

    Optional<ProjectTask> findById(UUID taskId);

    ProjectTaskPage findPageByProjectId(UUID projectId, int page, int size);

    boolean existsByWorkflowId(UUID workflowId);

    void deleteById(UUID taskId);
}
