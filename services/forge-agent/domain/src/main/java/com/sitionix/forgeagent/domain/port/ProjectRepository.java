package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    List<Project> findAllOrdered();

    Optional<Project> findById(UUID projectId);

    boolean existsByNormalizedName(String normalizedName);

    Project save(Project project);

    void deleteById(UUID projectId);
}
