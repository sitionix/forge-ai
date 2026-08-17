package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunExecutionEdge;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunExecutionEdgeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataConnectionResolutionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataNodeRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunExecutionEdgeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresWorkflowRunRepository implements WorkflowRunRepository {

    private static final List<String> ACTIVE_STATUSES = List.of(
            WorkflowRunStatus.QUEUED.name(),
            WorkflowRunStatus.RUNNING.name()
    );

    private final SpringDataWorkflowRunRepository workflowRunRepository;
    private final SpringDataNodeRunRepository nodeRunRepository;
    private final SpringDataConnectionResolutionRepository resolutionRepository;
    private final SpringDataWorkflowRunExecutionEdgeRepository executionEdgeRepository;
    private final WorkflowRunGraphRepository graphRepository;

    @Override
    public WorkflowRun save(final WorkflowRun run) {
        final WorkflowRunEntity saved = this.workflowRunRepository.save(this.toEntity(run));
        this.nodeRunRepository.saveAll(run.nodeRuns().stream()
                .map(PostgresNodeRunMapper::toEntity)
                .toList());
        return this.toSavedDomain(saved, run);
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
    public Optional<WorkflowRun> findLatestByTaskId(final UUID taskId) {
        return this.workflowRunRepository.findByTaskIdOrderByCreatedAtDescIdDesc(taskId).stream()
                .findFirst()
                .map(this::toLifecycleDomain);
    }

    @Override
    public List<WorkflowRunSummary> findSummariesBySourceWorkflowId(final UUID workflowId) {
        return this.workflowRunRepository.findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(workflowId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public List<WorkflowRunSummary> findSummariesByTaskId(final UUID taskId) {
        return this.workflowRunRepository.findByTaskIdOrderByCreatedAtDescIdDesc(taskId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public WorkflowRun saveLifecycle(final WorkflowRun run) {
        final WorkflowRunEntity entity = this.toEntity(run);
        this.workflowRunRepository.findById(run.id())
                .ifPresent(existing -> {
                    entity.setTaskInputPortId(existing.getTaskInputPortId());
                    entity.setTaskOutputPortId(existing.getTaskOutputPortId());
                    if (run.result() == null && existing.getResult() != null) {
                        entity.setResult(existing.getResult());
                    }
                    if (run.resultSourceNodeRunId() == null && existing.getResultSourceNodeRunId() != null) {
                        entity.setResultSourceNodeRunId(existing.getResultSourceNodeRunId());
                    }
                });
        return this.toLifecycleDomain(this.workflowRunRepository.save(entity));
    }

    @Override
    public boolean existsActiveByProjectId(final UUID projectId) {
        return this.workflowRunRepository.existsByProjectIdAndStatusIn(projectId, ACTIVE_STATUSES);
    }

    @Override
    public boolean existsActiveByTaskId(final UUID taskId) {
        return this.workflowRunRepository.existsByTaskIdAndStatusIn(taskId, ACTIVE_STATUSES);
    }

    @Override
    public boolean existsActiveBySourceWorkflowId(final UUID workflowId) {
        return this.workflowRunRepository.existsBySourceWorkflowIdAndStatusIn(workflowId, ACTIVE_STATUSES);
    }

    private WorkflowRunSummary toSummary(final WorkflowRunEntity entity) {
        return new WorkflowRunSummary(
                entity.getId(),
                entity.getSourceWorkflowId(),
                entity.getTaskId(),
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
                entity.getTaskId(),
                entity.getWorkflowName(),
                entity.getInput(),
                WorkflowRunStatus.valueOf(entity.getStatus()),
                this.nodeRunRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(entity.getId()).stream()
                        .map(PostgresNodeRunMapper::toDomain)
                        .toList(),
                this.resolutionRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(entity.getId()).stream()
                        .map(PostgresConnectionResolutionRepository::toDomain)
                        .toList(),
                this.executionEdgeRepository.findByWorkflowRunIdOrderBySourceNodeRunIdAscTargetNodeRunIdAsc(entity.getId()).stream()
                        .map(this::toExecutionEdge)
                        .toList(),
                this.runtimeGraphOrNull(entity.getId()),
                entity.getResult() == null ? null : new NodeRunOutput(entity.getResult()),
                entity.getResultSourceNodeRunId(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private WorkflowRun toSavedDomain(final WorkflowRunEntity entity, final WorkflowRun source) {
        return new WorkflowRun(
                entity.getId(),
                entity.getProjectId(),
                entity.getSourceWorkflowId(),
                entity.getTaskId(),
                entity.getWorkflowName(),
                entity.getInput(),
                WorkflowRunStatus.valueOf(entity.getStatus()),
                source.nodeRuns(),
                source.connectionResolutions(),
                source.executionEdges(),
                source.runtimeGraph(),
                source.result(),
                source.resultSourceNodeRunId(),
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
                entity.getTaskId(),
                entity.getWorkflowName(),
                entity.getInput(),
                WorkflowRunStatus.valueOf(entity.getStatus()),
                List.of(),
                List.of(),
                List.of(),
                null,
                entity.getResult() == null ? null : new NodeRunOutput(entity.getResult()),
                entity.getResultSourceNodeRunId(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private WorkflowRunGraph runtimeGraphOrNull(final UUID workflowRunId) {
        final WorkflowRunGraph graph = this.graphRepository.findByWorkflowRunId(workflowRunId);
        if (graph == null || graph.nodes().isEmpty()) {
            return null;
        }
        return graph;
    }

    private WorkflowRunEntity toEntity(final WorkflowRun run) {
        final WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setId(run.id());
        entity.setProjectId(run.projectId());
        entity.setSourceWorkflowId(run.sourceWorkflowId());
        entity.setTaskId(run.taskId());
        entity.setTaskInputPortId(run.runtimeGraph() == null ? null : run.runtimeGraph().taskInputPortId());
        entity.setTaskOutputPortId(run.runtimeGraph() == null ? null : run.runtimeGraph().taskOutputPortId());
        entity.setWorkflowName(run.workflowName());
        entity.setInput(run.input());
        entity.setStatus(run.status().name());
        entity.setCreatedAt(run.createdAt());
        entity.setStartedAt(run.startedAt());
        entity.setFinishedAt(run.finishedAt());
        entity.setResult(run.result() == null ? null : run.result().jsonValue());
        entity.setResultSourceNodeRunId(run.resultSourceNodeRunId());
        return entity;
    }

    private WorkflowRunExecutionEdge toExecutionEdge(final WorkflowRunExecutionEdgeEntity entity) {
        return new WorkflowRunExecutionEdge(
                entity.getWorkflowRunId(),
                entity.getSourceNodeRunId(),
                entity.getTargetNodeRunId(),
                entity.getSourceType()
        );
    }
}
