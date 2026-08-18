package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneAttempt;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceState;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LocalProjectWorkspacePort {

    Map<UUID, ProjectRepositoryWorkspaceState> resolveRepositoryWorkspaceStates(UUID projectId, List<ProjectRepositoryWorkspaceReference> repositories);

    ProjectRepositoryWorkspaceState resolveRepositoryWorkspaceState(UUID projectId, ProjectRepositoryWorkspaceReference repository);

    ProjectRepositoryCloneAttempt prepareCloneAttempt(UUID projectId, ProjectRepositoryWorkspaceReference repository);

    void finalizeCloneAttempt(ProjectRepositoryCloneAttempt attempt);

    void cleanupCloneAttempt(ProjectRepositoryCloneAttempt attempt);
}
