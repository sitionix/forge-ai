package com.sitionix.forgeagent.domain.model;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Immutable policy validation, shared by template writes and runtime snapshots. */
public final class ContextIterationPolicy {
    private ContextIterationPolicy() { }

    public static void validateGroup(NodeContextMode mode, String group) {
        if (mode.iterationScoped()
                ? group == null || group.isBlank() : group != null) {
            throw new ValidationException("INVALID_CONTEXT_GROUP", "Iteration context requires a non-blank group; other modes require no group.");
        }
    }

    public static void validateIdentity(NodeContextMode mode, UUID iteration) {
        if ((mode.iterationScoped()) != (iteration != null)) {
            throw new ValidationException("INVALID_CONTEXT_ITERATION", "Only iteration-scoped invocations require an iteration identity.");
        }
    }

    public static void validateSnapshots(List<RunNode> nodes) {
        final var groups = new HashMap<String, RunNode>();
        for (RunNode node : nodes) {
            if (node.contextGroupKey() == null) continue;
            final var prior = groups.putIfAbsent(node.contextGroupKey(), node);
            if (prior == null) continue;
            if (prior.contextMode() != node.contextMode()) {
                throw new ValidationException("CONTEXT_GROUP_MODE_CONFLICT", "A context group cannot mix shared and node-owned iteration modes.");
            }
            if (prior.scopeMode() != node.scopeMode()) {
                throw new ValidationException("CONTEXT_GROUP_SCOPE_CONFLICT", "All nodes in an iteration group must use the same scope mode.");
            }
            if (node.contextMode() == NodeContextMode.SHARED_SESSION_GROUP
                    && !java.util.Objects.equals(prior.executionModel(), node.executionModel())) {
                throw new ValidationException("CONTEXT_GROUP_CONFIGURATION_CONFLICT", "Shared group snapshots must use the same provider, model and effort.");
            }
        }
    }

    public static void validateScopes(List<Node> nodes) {
        final var modes = new HashMap<String, NodeContextMode>();
        final var scopes = new HashMap<String, NodeScopeMode>();
        for (Node node : nodes) {
            validateGroup(node.contextMode(), node.contextGroupKey());
            if (node.contextGroupKey() != null) {
                final var priorMode = modes.putIfAbsent(node.contextGroupKey(), node.contextMode());
                if (priorMode != null && priorMode != node.contextMode()) {
                    throw new ValidationException("CONTEXT_GROUP_MODE_CONFLICT", "A context group cannot mix shared and node-owned iteration modes.");
                }
                final var prior = scopes.putIfAbsent(node.contextGroupKey(), node.scopeMode());
                if (prior != null && prior != node.scopeMode()) {
                    throw new ValidationException("CONTEXT_GROUP_SCOPE_CONFLICT", "All nodes in an iteration group must use the same scope mode.");
                }
            }
        }
    }
}
