package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentNodeRun(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchemaDocument agentOutputSchema,
        NodeInputMode inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        AgentNodeRunStatus status,
        AgentNodeRunOutputDocument output,
        AgentNodeRunFailure failure,
        Instant routingCompletedAt,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public AgentNodeRun(final UUID id,
                        final UUID sourceNodeId,
                        final UUID sourceAgentId,
                        final String agentName,
                        final String agentInstructions,
                        final AgentOutputSchemaDocument agentOutputSchema,
                        final NodeInputMode inputMode,
                        final NodePosition position,
                        final UUID executionFrameId,
                        final UUID enteredViaInputPortId,
                        final UUID activationFrameId,
                        final UUID selectedOutputPortId,
                        final AgentNodeRunStatus status,
                        final AgentNodeRunOutputDocument output,
                        final AgentNodeRunFailure failure,
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

    public AgentNodeRun(final UUID id,
                        final UUID sourceNodeId,
                        final UUID sourceAgentId,
                        final String agentName,
                        final String agentInstructions,
                        final AgentOutputSchemaDocument agentOutputSchema,
                        final NodePosition position,
                        final AgentNodeRunStatus status,
                        final AgentNodeRunOutputDocument output,
                        final AgentNodeRunFailure failure,
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
                NodeInputMode.DEPENDENCIES_ONLY,
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
