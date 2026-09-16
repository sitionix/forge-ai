package com.sitionix.forgeagent.domain.model;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Immutable policy validation, shared by template writes and runtime snapshots. */
public final class ContextIterationPolicy {
    private ContextIterationPolicy() { }

    public static void validateGroup(NodeContextMode mode, String group) {
        if (mode == NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION
                ? group == null || group.isBlank() : group != null) {
            throw new ValidationException("INVALID_CONTEXT_GROUP", "Iteration context requires a non-blank group; other modes require no group.");
        }
    }

    public static void validateIdentity(NodeContextMode mode, UUID iteration) {
        if ((mode == NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION) != (iteration != null)) {
            throw new ValidationException("INVALID_CONTEXT_ITERATION", "Only iteration-scoped invocations require an iteration identity.");
        }
    }

    public static void validateScopes(List<Node> nodes) {
        final var scopes = new HashMap<String, NodeScopeMode>();
        for (Node node : nodes) {
            validateGroup(node.contextMode(), node.contextGroupKey());
            if (node.contextGroupKey() != null) {
                final var prior = scopes.putIfAbsent(node.contextGroupKey(), node.scopeMode());
                if (prior != null && prior != node.scopeMode()) {
                    throw new ValidationException("CONTEXT_GROUP_SCOPE_CONFLICT", "All nodes in an iteration group must use the same scope mode.");
                }
            }
        }
    }
}
