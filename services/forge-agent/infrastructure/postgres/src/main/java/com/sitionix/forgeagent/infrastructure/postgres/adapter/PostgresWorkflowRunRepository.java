package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataNodeRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunRepository;
import java.util.Arrays;
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
                .map(nodeRun -> this.toEntity(saved.getId(), nodeRun))
                .toList());
        return this.toDomain(saved);
    }

    @Override
    public Optional<WorkflowRun> findById(final UUID runId) {
        return this.workflowRunRepository.findById(runId).map(this::toDomain);
    }

    @Override
    public List<WorkflowRun> findBySourceWorkflowId(final UUID workflowId) {
        return this.workflowRunRepository.findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(workflowId).stream()
                .map(this::toDomain)
                .toList();
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
                        .map(this::toDomain)
                        .toList(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private NodeRun toDomain(final NodeRunEntity entity) {
        return new NodeRun(
                entity.getId(),
                entity.getSourceNodeId(),
                entity.getSourceAgentId(),
                entity.getAgentName(),
                entity.getAgentInstructions(),
                AgentOutputSchema.ofCanonicalJsonObject(entity.getAgentOutputSchema()),
                entity.getDependsOnNodeRunIds() == null ? List.of() : Arrays.asList(entity.getDependsOnNodeRunIds()),
                new NodePosition(entity.getPositionX(), entity.getPositionY()),
                NodeRunStatus.valueOf(entity.getStatus()),
                entity.getOutput() == null ? null : new NodeRunOutput(entity.getOutput()),
                entity.getFailureCode() == null && entity.getFailureMessage() == null
                        ? null
                        : new NodeRunFailure(entity.getFailureCode(), entity.getFailureMessage()),
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

    private NodeRunEntity toEntity(final UUID workflowRunId, final NodeRun nodeRun) {
        final NodeRunEntity entity = new NodeRunEntity();
        entity.setId(nodeRun.id());
        entity.setWorkflowRunId(workflowRunId);
        entity.setSourceNodeId(nodeRun.sourceNodeId());
        entity.setSourceAgentId(nodeRun.sourceAgentId());
        entity.setAgentName(nodeRun.agentName());
        entity.setAgentInstructions(nodeRun.agentInstructions());
        entity.setAgentOutputSchema(nodeRun.agentOutputSchema().jsonObject());
        entity.setDependsOnNodeRunIds(nodeRun.dependsOnNodeRunIds().toArray(UUID[]::new));
        entity.setPositionX(nodeRun.position().x());
        entity.setPositionY(nodeRun.position().y());
        entity.setStatus(nodeRun.status().name());
        entity.setOutput(nodeRun.output() == null ? null : nodeRun.output().jsonValue());
        entity.setFailureCode(nodeRun.failure() == null ? null : nodeRun.failure().code());
        entity.setFailureMessage(nodeRun.failure() == null ? null : nodeRun.failure().message());
        entity.setCreatedAt(nodeRun.createdAt());
        entity.setStartedAt(nodeRun.startedAt());
        entity.setFinishedAt(nodeRun.finishedAt());
        return entity;
    }
}
