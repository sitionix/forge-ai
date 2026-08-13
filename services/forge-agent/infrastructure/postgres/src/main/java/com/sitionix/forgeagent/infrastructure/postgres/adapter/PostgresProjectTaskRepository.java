package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.ProjectTask;
import com.sitionix.forgeagent.domain.port.ProjectTaskRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectTaskRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
    public List<ProjectTask> findByProjectId(final UUID projectId) {
        return this.taskRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(this::toDomain)
                .toList();
    }

    private ProjectTask toDomain(final ProjectTaskEntity entity) {
        return new ProjectTask(
                entity.getId(),
                entity.getProjectId(),
                entity.getTitle(),
                entity.getInput(),
                entity.getWorkflowId(),
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
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(task.updatedAt());
        return entity;
    }
}
