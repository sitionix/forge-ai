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
        UUID repositoryId
) {
    public NodeRunResponse(final UUID id, final UUID sourceNodeId, final UUID sourceAgentId, final String agentName,
                           final String agentInstructions, final JsonNode agentOutputSchema, final String inputMode,
                           final NodePositionResponse position, final UUID executionFrameId, final UUID enteredViaInputPortId,
                           final UUID activationFrameId, final UUID selectedOutputPortId, final NodeRunStatus status,
                           final JsonNode output, final NodeRunFailureResponse failure, final Instant createdAt,
                           final Instant startedAt, final Instant finishedAt) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, null);
    }
    public NodeRunResponse(final UUID id,
                           final UUID sourceNodeId,
                           final UUID sourceAgentId,
                           final String agentName,
                           final String agentInstructions,
                           final JsonNode agentOutputSchema,
                           final NodePositionResponse position,
                           final NodeRunStatus status,
                           final JsonNode output,
                           final NodeRunFailureResponse failure,
                           final Instant createdAt,
                           final Instant startedAt,
                           final Instant finishedAt) {
        this(
                id,
                sourceNodeId,
                sourceAgentId,
                agentName,
                agentInstructions,
                agentOutputSchema,
                null,
                position,
                null,
                null,
                null,
                null,
                status,
                output,
                failure,
                createdAt,
                startedAt,
                finishedAt,
                null
        );
    }
}
