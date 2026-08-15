package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
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
        AgentNodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant routingCompletedAt,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public NodeRunResponse(final UUID id,
                           final UUID sourceNodeId,
                           final UUID sourceAgentId,
                           final String agentName,
                           final String agentInstructions,
                           final JsonNode agentOutputSchema,
                           final String inputMode,
                           final NodePositionResponse position,
                           final UUID executionFrameId,
                           final UUID enteredViaInputPortId,
                           final UUID activationFrameId,
                           final UUID selectedOutputPortId,
                           final AgentNodeRunStatus status,
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
                inputMode,
                position,
                executionFrameId,
                enteredViaInputPortId,
                activationFrameId,
                selectedOutputPortId,
                status,
                output,
                failure,
                null,
                createdAt,
                startedAt,
                finishedAt
        );
    }

    public NodeRunResponse(final UUID id,
                           final UUID sourceNodeId,
                           final UUID sourceAgentId,
                           final String agentName,
                           final String agentInstructions,
                           final JsonNode agentOutputSchema,
                           final NodePositionResponse position,
                           final AgentNodeRunStatus status,
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
                null,
                createdAt,
                startedAt,
                finishedAt
        );
    }
}
