package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresProjectRepository implements ProjectRepository {

    private final SpringDataProjectRepository repository;

    @Override
    public List<Project> findAllOrdered() {
        return this.repository.findAllByOrderByNormalizedNameAscIdAsc().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Project> findById(final UUID projectId) {
        return this.repository.findById(projectId).map(this::toDomain);
    }

    @Override
    public boolean existsByNormalizedName(final String normalizedName) {
        return this.repository.existsByNormalizedName(normalizedName);
    }

    @Override
    public Project save(final Project project) {
        return this.toDomain(this.repository.save(this.toEntity(project)));
    }

    private Project toDomain(final ProjectEntity entity) {
        return new Project(entity.getId(), entity.getName(), entity.getNormalizedName(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private ProjectEntity toEntity(final Project project) {
        final ProjectEntity entity = new ProjectEntity();
        entity.setId(project.id());
        entity.setName(project.name());
        entity.setNormalizedName(project.normalizedName());
        entity.setCreatedAt(project.createdAt());
        entity.setUpdatedAt(project.updatedAt());
        return entity;
    }
}
