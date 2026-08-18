package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneAttempt;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LocalProjectWorkspacePort {

    Map<UUID, Boolean> resolveCloneStates(UUID projectId, List<ProjectRepositoryWorkspaceReference> repositories);

    ProjectRepositoryCloneAttempt prepareCloneAttempt(UUID projectId, ProjectRepositoryWorkspaceReference repository);

    void finalizeCloneAttempt(ProjectRepositoryCloneAttempt attempt);

    void cleanupCloneAttempt(ProjectRepositoryCloneAttempt attempt);
}
