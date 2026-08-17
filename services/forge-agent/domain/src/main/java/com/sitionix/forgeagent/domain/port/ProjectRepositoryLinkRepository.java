package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import java.util.List;
import java.util.UUID;

public interface ProjectRepositoryLinkRepository {

    ProjectRepositoryLink save(ProjectRepositoryLink repository);

    List<ProjectRepositoryLink> findByProjectId(UUID projectId);
}
