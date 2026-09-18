package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import java.time.Instant;
import java.util.UUID;

public record NodeRunResponse(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        JsonNode agentOutputSchema,
        String inputMode,
        NodePositionResponse position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        NodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        RecoveredNodeRunRetryEligibilityResponse retryEligibility,
        String contextGroupKey, UUID contextIterationId,
        String nodeType) {
    public NodeRunResponse {
        nodeType = nodeType == null ? "AGENT" : nodeType;
    }

    public NodeRunResponse(UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        JsonNode agentOutputSchema,
        String inputMode,
        NodePositionResponse position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        NodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        RecoveredNodeRunRetryEligibilityResponse retryEligibility,
        String contextGroupKey, UUID contextIterationId) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, contextMode, contextTrackingVersion,
                retryOfNodeRunId, retryEligibility, contextGroupKey, contextIterationId, "AGENT");
    }

    public NodeRunResponse(final UUID id, final UUID sourceNodeId, final UUID sourceAgentId,
                           final String agentName, final String agentInstructions, final JsonNode agentOutputSchema,
                           final String inputMode, final NodePositionResponse position, final UUID executionFrameId,
                           final UUID enteredViaInputPortId, final UUID activationFrameId, final UUID selectedOutputPortId,
                           final NodeRunStatus status, final JsonNode output, final NodeRunFailureResponse failure,
                           final Instant createdAt, final Instant startedAt, final Instant finishedAt,
                           final UUID repositoryId, final String contextMode, final Integer contextTrackingVersion) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, contextMode, contextTrackingVersion,
                null, new RecoveredNodeRunRetryEligibilityResponse("NONE", null));
    }

    public NodeRunResponse(final UUID id, final UUID sourceNodeId, final UUID sourceAgentId,
                           final String agentName, final String agentInstructions, final JsonNode agentOutputSchema,
                           final String inputMode, final NodePositionResponse position, final UUID executionFrameId,
                           final UUID enteredViaInputPortId, final UUID activationFrameId, final UUID selectedOutputPortId,
                           final NodeRunStatus status, final JsonNode output, final NodeRunFailureResponse failure,
                           final Instant createdAt, final Instant startedAt, final Instant finishedAt,
                           final UUID repositoryId) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, "FRESH_EACH_NODE_RUN", null,
                null, new RecoveredNodeRunRetryEligibilityResponse("NONE", null));
    }

    public NodeRunResponse(UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        JsonNode agentOutputSchema,
        String inputMode,
        NodePositionResponse position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        NodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        RecoveredNodeRunRetryEligibilityResponse retryEligibility) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, contextMode, contextTrackingVersion,
                retryOfNodeRunId, retryEligibility, null, null);
    }
}
