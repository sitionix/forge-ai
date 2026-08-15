package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record RunNode(
        UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeRunExecutionModel executionModel,
        NodeInputMode inputMode,
        NodePosition position
) {
}
