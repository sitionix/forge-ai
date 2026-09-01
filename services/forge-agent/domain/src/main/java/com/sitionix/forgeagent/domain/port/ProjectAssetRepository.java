package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ProjectAsset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectAssetRepository {
  List<ProjectAsset> findByProjectId(UUID projectId);
  Optional<ProjectAsset> findById(UUID id);
  ProjectAsset save(ProjectAsset asset);
  void delete(ProjectAsset asset);
}
