package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneTarget;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LocalProjectWorkspacePort {

    Map<UUID, Boolean> resolveCloneStates(UUID projectId, List<ProjectRepositoryWorkspaceReference> repositories);

    ProjectRepositoryCloneTarget resolveCloneTarget(UUID projectId, ProjectRepositoryWorkspaceReference repository);

    void ensureProjectWorkspace(UUID projectId);

    void cleanupCloneTarget(ProjectRepositoryCloneTarget target);
}
