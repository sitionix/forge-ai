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
        NodePosition position,
        NodeScopeMode scopeMode
) {
    public RunNode {
        scopeMode = scopeMode == null ? NodeScopeMode.GLOBAL : scopeMode;
    }

    public RunNode(final UUID workflowRunId, final UUID sourceNodeId, final UUID sourceAgentId,
                   final String agentName, final String agentInstructions, final AgentOutputSchema agentOutputSchema,
                   final NodeRunExecutionModel executionModel, final NodeInputMode inputMode, final NodePosition position) {
        this(workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                executionModel, inputMode, position, NodeScopeMode.GLOBAL);
    }
}
