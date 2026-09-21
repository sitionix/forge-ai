package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkingRepositoriesPolicyTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private Node node(boolean include, List<UUID> repositories, NodeScopeMode scope, NodeType type) {
        return new Node(UUID.randomUUID(), UUID.randomUUID(), NodeInputMode.DEPENDENCIES_ONLY,
                List.of(), List.of(), new NodePosition(0,0), scope,
                NodeContextMode.FRESH_EACH_NODE_RUN, null, type, include, repositories);
    }

    @Test
    void rejectsEmptyExplicitOnlyAndNonGlobalOverrides() {
        assertThatThrownBy(() -> node(false, List.of(), NodeScopeMode.GLOBAL, NodeType.AGENT))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> node(true, List.of(a), NodeScopeMode.PER_SCOPE, NodeType.AGENT))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> node(false, List.of(a), NodeScopeMode.GLOBAL, NodeType.MANUAL))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void configurationIsImmutableAndRejectsNulls() {
        var ids = new java.util.ArrayList<>(List.of(a));
        var node = node(false, ids, NodeScopeMode.GLOBAL, NodeType.AGENT);
        ids.clear();
        assertThat(node.workspaceRepositoryIds()).containsExactly(a);
        assertThatThrownBy(() -> node(true, null, NodeScopeMode.GLOBAL, NodeType.AGENT)).isInstanceOf(RuntimeException.class);
    }

    private RunNode runNode(NodeContextMode mode, List<UUID> ids) {
        return new RunNode(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "agent", "instructions",
                null, new NodeRunExecutionModel("codex", "model", null), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0,0), NodeScopeMode.GLOBAL, mode, "group", NodeType.AGENT, ids);
    }

    @Test
    void sharedSessionRequiresSameSetButNotSameOrder() {
        var first = runNode(NodeContextMode.SHARED_SESSION_GROUP, List.of(a,b));
        assertThatCode(() -> ContextIterationPolicy.validateSnapshots(List.of(first,
                runNode(NodeContextMode.SHARED_SESSION_GROUP, List.of(b,a))))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ContextIterationPolicy.validateSnapshots(List.of(first,
                runNode(NodeContextMode.SHARED_SESSION_GROUP, List.of(c)))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void nodeOwnedContextsAllowDifferentRepositories() {
        assertThatCode(() -> ContextIterationPolicy.validateSnapshots(List.of(
                runNode(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, List.of(a)),
                runNode(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, List.of(b)))))
                .doesNotThrowAnyException();
    }
}
