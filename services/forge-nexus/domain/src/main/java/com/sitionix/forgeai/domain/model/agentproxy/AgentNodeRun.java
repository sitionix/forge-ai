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
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId
) {
}
