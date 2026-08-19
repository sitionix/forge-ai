package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.ProjectTask;
import com.sitionix.forgeagent.domain.model.ProjectTaskPage;
import com.sitionix.forgeagent.domain.port.ProjectTaskRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectTaskRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresProjectTaskRepository implements ProjectTaskRepository {

    private final SpringDataProjectTaskRepository taskRepository;

    @Override
    public ProjectTask save(final ProjectTask task) {
        return this.toDomain(this.taskRepository.save(this.toEntity(task)));
    }

    @Override
    public Optional<ProjectTask> findById(final UUID taskId) {
        return this.taskRepository.findById(taskId).map(this::toDomain);
    }

    @Override
    public ProjectTaskPage findPageByProjectId(final UUID projectId, final int page, final int size) {
        final Page<ProjectTaskEntity> result = this.taskRepository.findByProjectId(
                projectId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        );
        return new ProjectTaskPage(
                result.getContent().stream().map(this::toDomain).toList(),
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Override
    public boolean existsByWorkflowId(final UUID workflowId) {
        return this.taskRepository.existsByWorkflowId(workflowId);
    }

    @Override
    public void deleteById(final UUID taskId) {
        this.taskRepository.deleteById(taskId);
    }

    private ProjectTask toDomain(final ProjectTaskEntity entity) {
        return new ProjectTask(
                entity.getId(),
                entity.getProjectId(),
                entity.getTitle(),
                entity.getInput(),
                entity.getWorkflowId(),
                List.copyOf(entity.getRepositoryIds()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ProjectTaskEntity toEntity(final ProjectTask task) {
        final ProjectTaskEntity entity = new ProjectTaskEntity();
        entity.setId(task.id());
        entity.setProjectId(task.projectId());
        entity.setTitle(task.title());
        entity.setInput(task.input());
        entity.setWorkflowId(task.workflowId());
        entity.setRepositoryIds(new java.util.ArrayList<>(task.repositoryIds()));
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(task.updatedAt());
        return entity;
    }
}
