package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.UUID;
import java.util.Objects;

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
        NodeScopeMode scopeMode,
        NodeContextMode contextMode,
        String contextGroupKey,
        NodeType nodeType,
        List<UUID> resolvedWorkspaceRepositoryIds) {
    public RunNode(
        UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeRunExecutionModel executionModel,
        NodeInputMode inputMode,
        NodePosition position,
        NodeScopeMode scopeMode,
        NodeContextMode contextMode,
        String contextGroupKey,
        NodeType nodeType) {
        this(workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, executionModel, inputMode, position, scopeMode, contextMode, contextGroupKey, nodeType, java.util.List.of());
    }

    public RunNode(UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeRunExecutionModel executionModel,
        NodeInputMode inputMode,
        NodePosition position,
        NodeScopeMode scopeMode,
        NodeContextMode contextMode,
        String contextGroupKey) {
        this(workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                executionModel, inputMode, position, scopeMode, contextMode, contextGroupKey, NodeType.AGENT);
    }

    public RunNode {
        nodeType = nodeType == null ? NodeType.AGENT : nodeType;
        Objects.requireNonNull(scopeMode, "scopeMode must not be null");
        contextMode = NodeContextMode.legacyDefault(contextMode);
        ContextIterationPolicy.validateGroup(contextMode, contextGroupKey);
        resolvedWorkspaceRepositoryIds = java.util.List.copyOf(resolvedWorkspaceRepositoryIds);
    }

    public RunNode(final UUID workflowRunId, final UUID sourceNodeId, final UUID sourceAgentId,
                   final String agentName, final String agentInstructions,
                   final AgentOutputSchema agentOutputSchema, final NodeRunExecutionModel executionModel,
                   final NodeInputMode inputMode, final NodePosition position, final NodeScopeMode scopeMode) {
        this(workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                executionModel, inputMode, position, scopeMode, NodeContextMode.FRESH_EACH_NODE_RUN);
    }


    public RunNode(UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeRunExecutionModel executionModel,
        NodeInputMode inputMode,
        NodePosition position,
        NodeScopeMode scopeMode,
        NodeContextMode contextMode) {
        this(workflowRunId, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                executionModel, inputMode, position, scopeMode, contextMode, null);
    }
}
