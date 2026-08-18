package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectRepositoryLinkRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresProjectRepositoryLinkRepository implements ProjectRepositoryLinkRepository {

    private final SpringDataProjectRepositoryLinkRepository repository;

    @Override
    public ProjectRepositoryLink save(final ProjectRepositoryLink projectRepository) {
        return this.toDomain(this.repository.save(this.toEntity(projectRepository)));
    }

    @Override
    public List<ProjectRepositoryLink> findByProjectId(final UUID projectId) {
        return this.repository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<ProjectRepositoryLink> findById(final UUID repositoryId) {
        return this.repository.findById(repositoryId).map(this::toDomain);
    }

    private ProjectRepositoryLink toDomain(final ProjectRepositoryEntity entity) {
        return new ProjectRepositoryLink(entity.getId(), entity.getProjectId(), entity.getRemoteUrl(), entity.getCreatedAt());
    }

    private ProjectRepositoryEntity toEntity(final ProjectRepositoryLink projectRepository) {
        final ProjectRepositoryEntity entity = new ProjectRepositoryEntity();
        entity.setId(projectRepository.id());
        entity.setProjectId(projectRepository.projectId());
        entity.setRemoteUrl(projectRepository.remoteUrl());
        entity.setCreatedAt(projectRepository.createdAt());
        return entity;
    }
}
