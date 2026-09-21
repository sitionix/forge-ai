package com.sitionix.forgeagent.domain.model;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        NodeInputMode inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        NodeScopeMode scopeMode,
        NodeContextMode contextMode,
        String contextGroupKey,
        NodeType nodeType,
        boolean includeTaskRepositories,
        List<UUID> workspaceRepositoryIds) {
    public Node(
        UUID id,
        UUID targetId,
        NodeInputMode inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        NodeScopeMode scopeMode,
        NodeContextMode contextMode,
        String contextGroupKey,
        NodeType nodeType) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, nodeType, true, List.of());
    }

    public Node(UUID id,
        UUID targetId,
        NodeInputMode inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        NodeScopeMode scopeMode,
        NodeContextMode contextMode,
        String contextGroupKey) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, NodeType.AGENT);
    }

    public Node {
        nodeType = nodeType == null ? NodeType.AGENT : nodeType;
        Objects.requireNonNull(scopeMode, "scopeMode must not be null");
        contextMode = NodeContextMode.legacyDefault(contextMode);
        ContextIterationPolicy.validateGroup(contextMode, contextGroupKey);
        workspaceRepositoryIds = List.copyOf(workspaceRepositoryIds);
        if ((!includeTaskRepositories && workspaceRepositoryIds.isEmpty())
                || ((nodeType != NodeType.AGENT || scopeMode != NodeScopeMode.GLOBAL)
                    && (!includeTaskRepositories || !workspaceRepositoryIds.isEmpty()))) {
            throw new ValidationException(
                    "INVALID_WORKSPACE_REPOSITORIES", "Only GLOBAL Agent nodes may override working repositories; explicit-only selection must not be empty.");
        }
    }

    public Node(final UUID id,
                final UUID targetId,
                final NodeInputMode inputMode,
                final List<NodePort> inputs,
                final List<NodePort> outputs,
                final NodePosition position,
                final NodeScopeMode scopeMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, NodeContextMode.FRESH_EACH_NODE_RUN);
    }


    public Node(UUID id,
        UUID targetId,
        NodeInputMode inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        NodeScopeMode scopeMode,
        NodeContextMode contextMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, null);
    }
}
