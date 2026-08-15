package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunNode(
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchemaDocument agentOutputSchema,
        NodeInputMode inputMode,
        NodePosition position
) {
}
