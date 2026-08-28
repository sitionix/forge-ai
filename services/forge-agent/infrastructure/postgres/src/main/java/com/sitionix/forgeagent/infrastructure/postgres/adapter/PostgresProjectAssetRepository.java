package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.ProjectAsset;
import com.sitionix.forgeagent.domain.port.ProjectAssetRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectAssetEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectAssetRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresProjectAssetRepository implements ProjectAssetRepository {
  private final SpringDataProjectAssetRepository repository;
  public List<ProjectAsset> findByProjectId(UUID id) { return repository.findByProjectIdOrderByCreatedAtAscIdAsc(id).stream().map(this::domain).toList(); }
  public Optional<ProjectAsset> findById(UUID id) { return repository.findById(id).map(this::domain); }
  public ProjectAsset save(ProjectAsset a) { var e = new ProjectAssetEntity(); e.setId(a.id()); e.setProjectId(a.projectId()); e.setName(a.name()); e.setSshConnectionId(a.sshConnectionId()); e.setCreatedAt(a.createdAt()); e.setUpdatedAt(a.updatedAt()); return domain(repository.save(e)); }
  public void delete(ProjectAsset a) { repository.deleteById(a.id()); }
  private ProjectAsset domain(ProjectAssetEntity e) { return new ProjectAsset(e.getId(), e.getProjectId(), e.getName(), e.getSshConnectionId(), e.getCreatedAt(), e.getUpdatedAt()); }
}
