package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ProjectTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectTaskRepository {

    ProjectTask save(ProjectTask task);

    Optional<ProjectTask> findById(UUID taskId);

    List<ProjectTask> findByProjectId(UUID projectId);
}
