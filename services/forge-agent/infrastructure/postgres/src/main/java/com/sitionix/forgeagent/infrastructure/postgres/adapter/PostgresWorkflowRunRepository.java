package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataNodeRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresWorkflowRunRepository implements WorkflowRunRepository {

    private final SpringDataWorkflowRunRepository workflowRunRepository;
    private final SpringDataNodeRunRepository nodeRunRepository;

    @Override
    public WorkflowRun save(final WorkflowRun run) {
        final WorkflowRunEntity saved = this.workflowRunRepository.save(this.toEntity(run));
        this.nodeRunRepository.saveAll(run.nodeRuns().stream()
                .map(PostgresNodeRunMapper::toEntity)
                .toList());
        return this.toDomain(saved);
    }

    @Override
    public Optional<WorkflowRun> findById(final UUID runId) {
        return this.workflowRunRepository.findById(runId).map(this::toDomain);
    }

    @Override
    public Optional<WorkflowRun> findByIdForUpdate(final UUID runId) {
        return this.workflowRunRepository.findByIdForUpdate(runId).map(this::toLifecycleDomain);
    }

    @Override
    public List<WorkflowRunSummary> findSummariesBySourceWorkflowId(final UUID workflowId) {
        return this.workflowRunRepository.findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(workflowId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public WorkflowRun saveLifecycle(final WorkflowRun run) {
        return this.toLifecycleDomain(this.workflowRunRepository.save(this.toEntity(run)));
    }

    private WorkflowRunSummary toSummary(final WorkflowRunEntity entity) {
        return new WorkflowRunSummary(
                entity.getId(),
                entity.getSourceWorkflowId(),
                entity.getWorkflowName(),
                WorkflowRunStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private WorkflowRun toDomain(final WorkflowRunEntity entity) {
        return new WorkflowRun(
                entity.getId(),
                entity.getProjectId(),
                entity.getSourceWorkflowId(),
                entity.getWorkflowName(),
                entity.getInput(),
                WorkflowRunStatus.valueOf(entity.getStatus()),
                this.nodeRunRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(entity.getId()).stream()
                        .map(PostgresNodeRunMapper::toDomain)
                        .toList(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private WorkflowRun toLifecycleDomain(final WorkflowRunEntity entity) {
        return new WorkflowRun(
                entity.getId(),
                entity.getProjectId(),
                entity.getSourceWorkflowId(),
                entity.getWorkflowName(),
                entity.getInput(),
                WorkflowRunStatus.valueOf(entity.getStatus()),
                List.of(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private WorkflowRunEntity toEntity(final WorkflowRun run) {
        final WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setId(run.id());
        entity.setProjectId(run.projectId());
        entity.setSourceWorkflowId(run.sourceWorkflowId());
        entity.setWorkflowName(run.workflowName());
        entity.setInput(run.input());
        entity.setStatus(run.status().name());
        entity.setCreatedAt(run.createdAt());
        entity.setStartedAt(run.startedAt());
        entity.setFinishedAt(run.finishedAt());
        return entity;
    }
}
