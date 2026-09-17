package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ContextIterationPolicy;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SharedContextPolicyTest {
    private static final UUID RUN = UUID.randomUUID();
    private static final NodeRunExecutionModel MODEL = new NodeRunExecutionModel("codex", "model", "medium");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void sharedGroupRequiresNonBlankKey(String group) {
        assertThatThrownBy(() -> ContextIterationPolicy.validateGroup(NodeContextMode.SHARED_SESSION_GROUP, group))
                .extracting("code").isEqualTo("INVALID_CONTEXT_GROUP");
    }

    @Test
    void sharedGroupRequiresIterationIdentity() {
        assertThatThrownBy(() -> ContextIterationPolicy.validateIdentity(NodeContextMode.SHARED_SESSION_GROUP, null))
                .extracting("code").isEqualTo("INVALID_CONTEXT_ITERATION");
        assertThatCode(() -> ContextIterationPolicy.validateIdentity(NodeContextMode.SHARED_SESSION_GROUP, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"other,model,medium", "codex,other,medium", "codex,model,high", "codex,model,"})
    void immutableGraphRejectsEachProviderModelOrEffortMismatch(String provider, String model, String effort) {
        var first = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.GLOBAL, "loop", MODEL);
        var second = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.GLOBAL, "loop",
                new NodeRunExecutionModel(provider, model, effort));
        assertThatThrownBy(() -> graph(first, second))
                .extracting("code").isEqualTo("CONTEXT_GROUP_CONFIGURATION_CONFLICT");
    }

    @Test
    void templateAndImmutableGraphRejectMixedContextModes() {
        var first = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.GLOBAL, "loop", MODEL);
        var second = snapshot(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, NodeScopeMode.GLOBAL, "loop", MODEL);
        assertThatThrownBy(() -> graph(first, second)).extracting("code").isEqualTo("CONTEXT_GROUP_MODE_CONFLICT");
        assertThatThrownBy(() -> ContextIterationPolicy.validateScopes(List.of(template(first), template(second))))
                .extracting("code").isEqualTo("CONTEXT_GROUP_MODE_CONFLICT");
    }

    @Test
    void nodeOwnedIterationAcceptsMixedScopesAndDifferentModels() {
        var first = snapshot(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, NodeScopeMode.GLOBAL, "loop", MODEL);
        var second = snapshot(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, NodeScopeMode.PER_SCOPE, "loop",
                new NodeRunExecutionModel("other", "other", "high"));
        assertThatCode(() -> graph(first, second)).doesNotThrowAnyException();
        assertThatCode(() -> ContextIterationPolicy.validateScopes(List.of(template(first), template(second))))
                .doesNotThrowAnyException();
    }

    @Test
    void templateAndImmutableGraphRejectMixedRepositoryScopeModes() {
        var first = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.GLOBAL, "loop", MODEL);
        var second = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.PER_SCOPE, "loop", MODEL);
        assertThatThrownBy(() -> graph(first, second)).extracting("code").isEqualTo("CONTEXT_GROUP_SCOPE_CONFLICT");
        assertThatThrownBy(() -> ContextIterationPolicy.validateScopes(List.of(template(first), template(second))))
                .extracting("code").isEqualTo("CONTEXT_GROUP_SCOPE_CONFLICT");
    }

    @Test
    void differentAgentsInstructionsAndSchemasRemainSeparateSnapshotsInAcceptedSharedGraph() {
        var first = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.GLOBAL, "loop", MODEL);
        var second = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.GLOBAL, "loop", MODEL);
        assertThat(first.sourceAgentId()).isNotEqualTo(second.sourceAgentId());
        assertThat(first.agentInstructions()).isNotEqualTo(second.agentInstructions());
        assertThat(first.agentOutputSchema()).isNotEqualTo(second.agentOutputSchema());
        assertThat(graph(first, second).nodes()).containsExactly(first, second);
        assertThatCode(() -> ContextIterationPolicy.validateScopes(List.of(template(first), template(second))))
                .doesNotThrowAnyException();
    }

    @Test
    void independentGroupsAndLegacyIterationNodesMayUseDifferentModels() {
        var first = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.GLOBAL, "loop", MODEL);
        var second = snapshot(NodeContextMode.SHARED_SESSION_GROUP, NodeScopeMode.PER_SCOPE, "other",
                new NodeRunExecutionModel("other", "other", null));
        assertThatCode(() -> graph(first, second)).doesNotThrowAnyException();
        assertThatCode(() -> graph(
                snapshot(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, NodeScopeMode.GLOBAL, "loop", MODEL),
                snapshot(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, NodeScopeMode.GLOBAL, "loop", second.executionModel())))
                .doesNotThrowAnyException();
    }

    private WorkflowRunGraph graph(RunNode... nodes) {
        return new WorkflowRunGraph(RUN, UUID.randomUUID(), List.of(nodes), List.of(), List.of());
    }

    private RunNode snapshot(NodeContextMode mode, NodeScopeMode scope, String group, NodeRunExecutionModel model) {
        var agent = UUID.randomUUID();
        return new RunNode(RUN, UUID.randomUUID(), agent, "Agent " + agent, "Instructions " + agent,
                new AgentOutputSchema("{\"type\":\"object\",\"description\":\"" + agent + "\"}"), model,
                NodeInputMode.DEPENDENCIES_ONLY, new NodePosition(0, 0), scope, mode, group);
    }

    private Node template(RunNode node) {
        return new Node(node.sourceNodeId(), node.sourceAgentId(), node.inputMode(), List.of(), List.of(),
                node.position(), node.scopeMode(), node.contextMode(), node.contextGroupKey());
    }
}
