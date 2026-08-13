package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import java.util.Arrays;
import java.util.List;

final class PostgresNodeRunMapper {

    private PostgresNodeRunMapper() {
    }

    static NodeRun toDomain(final NodeRunEntity entity) {
        return new NodeRun(
                entity.getId(),
                entity.getWorkflowRunId(),
                entity.getSourceNodeId(),
                entity.getSourceAgentId(),
                entity.getAgentName(),
                entity.getAgentInstructions(),
                AgentOutputSchema.ofCanonicalJsonObject(entity.getAgentOutputSchema()),
                entity.getDependsOnNodeRunIds() == null ? List.of() : Arrays.asList(entity.getDependsOnNodeRunIds()),
                inputMode(entity.getInputMode()),
                new NodePosition(entity.getPositionX(), entity.getPositionY()),
                NodeRunStatus.valueOf(entity.getStatus()),
                entity.getOutput() == null ? null : new NodeRunOutput(entity.getOutput()),
                entity.getFailureCode() == null && entity.getFailureMessage() == null
                        ? null
                        : new NodeRunFailure(entity.getFailureCode(), entity.getFailureMessage()),
                toExecutionModel(entity),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    static NodeRunEntity toEntity(final NodeRun nodeRun) {
        final NodeRunEntity entity = new NodeRunEntity();
        entity.setId(nodeRun.id());
        entity.setWorkflowRunId(nodeRun.workflowRunId());
        entity.setSourceNodeId(nodeRun.sourceNodeId());
        entity.setSourceAgentId(nodeRun.sourceAgentId());
        entity.setAgentName(nodeRun.agentName());
        entity.setAgentInstructions(nodeRun.agentInstructions());
        entity.setAgentOutputSchema(nodeRun.agentOutputSchema().jsonObject());
        entity.setDependsOnNodeRunIds(nodeRun.dependsOnNodeRunIds().toArray(java.util.UUID[]::new));
        entity.setInputMode(inputMode(nodeRun.inputMode()).name());
        entity.setPositionX(nodeRun.position().x());
        entity.setPositionY(nodeRun.position().y());
        entity.setStatus(nodeRun.status().name());
        entity.setOutput(nodeRun.output() == null ? null : nodeRun.output().jsonValue());
        entity.setFailureCode(nodeRun.failure() == null ? null : nodeRun.failure().code());
        entity.setFailureMessage(nodeRun.failure() == null ? null : nodeRun.failure().message());
        entity.setExecutionModelProviderId(nodeRun.executionModel() == null ? null : nodeRun.executionModel().providerId());
        entity.setExecutionModelId(nodeRun.executionModel() == null ? null : nodeRun.executionModel().modelId());
        entity.setExecutionModelEffortId(nodeRun.executionModel() == null ? null : nodeRun.executionModel().effortId());
        entity.setCreatedAt(nodeRun.createdAt());
        entity.setStartedAt(nodeRun.startedAt());
        entity.setFinishedAt(nodeRun.finishedAt());
        return entity;
    }

    private static NodeRunExecutionModel toExecutionModel(final NodeRunEntity entity) {
        if (entity.getExecutionModelProviderId() == null
                && entity.getExecutionModelId() == null
                && entity.getExecutionModelEffortId() == null) {
            return null;
        }
        return new NodeRunExecutionModel(
                entity.getExecutionModelProviderId(),
                entity.getExecutionModelId(),
                entity.getExecutionModelEffortId()
        );
    }

    private static NodeInputMode inputMode(final NodeInputMode inputMode) {
        return inputMode == null ? NodeInputMode.DEPENDENCIES_ONLY : inputMode;
    }

    private static NodeInputMode inputMode(final String inputMode) {
        if (inputMode == null || inputMode.isBlank()) {
            return NodeInputMode.TASK_AND_DEPENDENCIES;
        }
        return NodeInputMode.valueOf(inputMode);
    }
}
