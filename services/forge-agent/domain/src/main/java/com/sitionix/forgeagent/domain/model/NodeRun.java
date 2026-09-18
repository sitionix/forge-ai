package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record NodeRun(
        UUID id,
        UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeInputMode inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        Instant routingCompletedAt,
        NodeRunStatus status,
        NodeRunOutput output,
        NodeRunFailure failure,
        NodeRunExecutionModel executionModel,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        NodeContextMode contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        String contextGroupKey, UUID contextIterationId,
        NodeType nodeType) {
    public NodeRun(UUID id,
        UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeInputMode inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        Instant routingCompletedAt,
        NodeRunStatus status,
        NodeRunOutput output,
        NodeRunFailure failure,
        NodeRunExecutionModel executionModel,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        NodeContextMode contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        String contextGroupKey, UUID contextIterationId) {
        this(id, workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                inputMode, position, executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId,
                routingCompletedAt, status, output, failure, executionModel, createdAt, startedAt, finishedAt,
                repositoryId, contextMode, contextTrackingVersion, retryOfNodeRunId, contextGroupKey,
                contextIterationId, NodeType.AGENT);
    }

    public NodeRun {
        nodeType = nodeType == null ? NodeType.AGENT : nodeType;
        contextMode = NodeContextMode.legacyDefault(contextMode);
        ContextIterationPolicy.validateGroup(contextMode, contextGroupKey);
        ContextIterationPolicy.validateIdentity(contextMode, contextIterationId);
    }

    public NodeRun(final UUID id, final UUID workflowRunId, final UUID sourceNodeId, final UUID sourceAgentId,
                   final String agentName, final String agentInstructions, final AgentOutputSchema agentOutputSchema,
                   final NodeInputMode inputMode, final NodePosition position, final UUID executionFrameId,
                   final UUID enteredViaInputPortId, final UUID activationFrameId, final UUID selectedOutputPortId,
                   final Instant routingCompletedAt, final NodeRunStatus status, final NodeRunOutput output,
                   final NodeRunFailure failure, final NodeRunExecutionModel executionModel, final Instant createdAt,
                   final Instant startedAt, final Instant finishedAt, final UUID repositoryId,
                   final NodeContextMode contextMode, final Integer contextTrackingVersion) {
        this(id, workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                inputMode, position, executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId,
                routingCompletedAt, status, output, failure, executionModel, createdAt, startedAt, finishedAt,
                repositoryId, contextMode, contextTrackingVersion, null);
    }

    public NodeRun(final UUID id, final UUID workflowRunId, final UUID sourceNodeId, final UUID sourceAgentId,
                   final String agentName, final String agentInstructions, final AgentOutputSchema agentOutputSchema,
                   final NodeInputMode inputMode, final NodePosition position, final UUID executionFrameId,
                   final UUID enteredViaInputPortId, final UUID activationFrameId, final UUID selectedOutputPortId,
                   final Instant routingCompletedAt, final NodeRunStatus status, final NodeRunOutput output,
                   final NodeRunFailure failure, final NodeRunExecutionModel executionModel, final Instant createdAt,
                   final Instant startedAt, final Instant finishedAt, final UUID repositoryId) {
        this(id, workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                inputMode, position, executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId,
                routingCompletedAt, status, output, failure, executionModel, createdAt, startedAt, finishedAt,
                repositoryId, NodeContextMode.FRESH_EACH_NODE_RUN, null, null);
    }

    public NodeRun(UUID id,
        UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeInputMode inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        Instant routingCompletedAt,
        NodeRunStatus status,
        NodeRunOutput output,
        NodeRunFailure failure,
        NodeRunExecutionModel executionModel,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        NodeContextMode contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId) {
        this(id, workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode,
                position, executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId,
                routingCompletedAt, status, output, failure, executionModel, createdAt, startedAt, finishedAt,
                repositoryId, contextMode, contextTrackingVersion, retryOfNodeRunId, null, null);
    }
}
