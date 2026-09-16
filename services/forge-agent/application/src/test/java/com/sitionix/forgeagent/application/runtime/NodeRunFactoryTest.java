package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeRunFactoryTest {

    @Test
    void absentLegacyContextPolicyNormalizesToFresh() {
        assertThat(NodeContextMode.legacyDefault(null)).isEqualTo(NodeContextMode.FRESH_EACH_NODE_RUN);
    }

    @Test
    void copiesSnapshottedContextPolicyAndMarksRuntimeTracking() {
        final WorkflowRun workflowRun = this.workflowRun();
        final RunNode runNode = this.node(NodeScopeMode.GLOBAL, NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE);

        final NodeRun nodeRun = this.factory.root(workflowRun, this.frame, runNode, UUID.randomUUID(), null);

        assertThat(nodeRun.contextMode()).isEqualTo(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE);
        assertThat(nodeRun.contextTrackingVersion()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidTemplateGroupsAndMixedScopes() {
        var mode = NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION;
        for (String group : java.util.Arrays.asList(null, "", " \t\n")) {
            assertThatThrownBy(() -> com.sitionix.forgeagent.domain.model.ContextIterationPolicy.validateGroup(mode, group))
                    .extracting("code").isEqualTo("INVALID_CONTEXT_GROUP");
        }
        for (var existing : List.of(NodeContextMode.FRESH_EACH_NODE_RUN, NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE)) {
            assertThatThrownBy(() -> com.sitionix.forgeagent.domain.model.ContextIterationPolicy.validateGroup(existing, "group"))
                    .extracting("code").isEqualTo("INVALID_CONTEXT_GROUP");
        }
        var nodes = List.of(
                new com.sitionix.forgeagent.domain.model.Node(UUID.randomUUID(), UUID.randomUUID(), NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(), List.of(), new NodePosition(0, 0), NodeScopeMode.GLOBAL, mode, "group"),
                new com.sitionix.forgeagent.domain.model.Node(UUID.randomUUID(), UUID.randomUUID(), NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(), List.of(), new NodePosition(0, 0), NodeScopeMode.PER_SCOPE, mode, "group"));
        assertThatThrownBy(() -> com.sitionix.forgeagent.domain.model.ContextIterationPolicy.validateScopes(nodes))
                .extracting("code").isEqualTo("CONTEXT_GROUP_SCOPE_CONFLICT");
    }

    @Test
    void feedbackPreservesIterationAcrossFramesAndExitStartsNewIdentity() {
        var impl = iterationNode(NodeScopeMode.GLOBAL);
        var reviewer = iterationNode(NodeScopeMode.GLOBAL);
        var first = factory.root(workflowRun(), frame, impl, UUID.randomUUID(), null);
        var review = factory.activated(workflowRun(), frame, frame, reviewer, UUID.randomUUID(), null, List.of(first));
        var child = new ExecutionFrame(UUID.randomUUID(), RUN_ID, frame.id(), NOW);
        var feedback = factory.activated(workflowRun(), child, frame, impl, UUID.randomUUID(), null, List.of(review));
        assertThat(feedback.executionFrameId()).isNotEqualTo(first.executionFrameId());
        assertThat(feedback.contextIterationId()).isEqualTo(first.contextIterationId()).isEqualTo(review.contextIterationId());
        var outside = factory.activated(workflowRun(), child, child, node(NodeScopeMode.GLOBAL), UUID.randomUUID(), null, List.of(review));
        assertThat(outside.contextIterationId()).isNull();
        var second = factory.activated(workflowRun(), child, child, impl, UUID.randomUUID(), null, List.of(outside));
        assertThat(second.contextIterationId()).isNotEqualTo(first.contextIterationId());
        assertThat(second.contextGroupKey()).isEqualTo("implementation-review");
    }

    @Test
    void fanInContinuesOneIdentityWithOutsideInputAndRejectsTwo() {
        var target = iterationNode(NodeScopeMode.GLOBAL);
        var first = factory.root(workflowRun(), frame, target, UUID.randomUUID(), null);
        var other = factory.root(workflowRun(), frame, target, UUID.randomUUID(), null);
        var outside = factory.root(workflowRun(), frame, node(NodeScopeMode.GLOBAL), UUID.randomUUID(), null);
        assertThat(factory.activated(workflowRun(), frame, frame, target, UUID.randomUUID(), null, List.of(first, outside))
                .contextIterationId()).isEqualTo(first.contextIterationId());
        assertThatThrownBy(() -> factory.activated(workflowRun(), frame, frame, target, UUID.randomUUID(), null, List.of(first, other)))
                .extracting("code").isEqualTo("AGENT_CONTEXT_ITERATION_CONFLICT");
    }

    @Test
    void repositoryBoundaryDoesNotPropagateIdentity() {
        var target = iterationNode(NodeScopeMode.PER_SCOPE);
        var source = factory.root(workflowRun(), frame, target, UUID.randomUUID(), REPOSITORY_ID);
        assertThat(NodeRunFactory.iteration(workflowRun(), target, OUTSIDE_REPOSITORY_ID, List.of(source)))
                .isNotEqualTo(source.contextIterationId());
    }

    private RunNode iterationNode(NodeScopeMode scope) {
        var n = node(scope);
        return new RunNode(n.workflowRunId(), n.sourceNodeId(), n.sourceAgentId(), n.agentName(), n.agentInstructions(),
                n.agentOutputSchema(), n.executionModel(), n.inputMode(), n.position(), scope,
                NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, "implementation-review");
    }

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OUTSIDE_REPOSITORY_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private final NodeRunFactory factory = new NodeRunFactory(
            Clock.fixed(NOW, ZoneOffset.UTC), new ScopeProjectionPolicy());
    private final ExecutionFrame frame = new ExecutionFrame(UUID.randomUUID(), RUN_ID, null, NOW);

    @Test
    void globalInvocationRequiresNullRepository() {
        assertThat(this.factory.root(this.workflowRun(), this.frame, this.node(NodeScopeMode.GLOBAL), UUID.randomUUID(), null)
                .repositoryId()).isNull();
        this.assertInvalid(NodeScopeMode.GLOBAL, REPOSITORY_ID, "INVALID_GLOBAL_NODE_RUN_SCOPE");
    }

    @Test
    void perScopeInvocationRequiresRepositoryFromSnapshot() {
        assertThat(this.factory.root(this.workflowRun(), this.frame, this.node(NodeScopeMode.PER_SCOPE),
                UUID.randomUUID(), REPOSITORY_ID).repositoryId()).isEqualTo(REPOSITORY_ID);
        this.assertInvalid(NodeScopeMode.PER_SCOPE, null, "MISSING_NODE_RUN_REPOSITORY");
        this.assertInvalid(NodeScopeMode.PER_SCOPE, OUTSIDE_REPOSITORY_ID, "NODE_RUN_REPOSITORY_OUTSIDE_SNAPSHOT");
    }

    private void assertInvalid(final NodeScopeMode mode, final UUID repositoryId, final String code) {
        assertThatThrownBy(() -> this.factory.root(
                this.workflowRun(), this.frame, this.node(mode), UUID.randomUUID(), repositoryId))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo(code);
    }

    private RunNode node(final NodeScopeMode mode) {
        return this.node(mode, NodeContextMode.FRESH_EACH_NODE_RUN);
    }

    private RunNode node(final NodeScopeMode mode, final NodeContextMode contextMode) {
        return new RunNode(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Agent", "Work.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                new NodeRunExecutionModel("codex", "model", null), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0), mode, contextMode);
    }

    private WorkflowRun workflowRun() {
        return new WorkflowRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), null, "Workflow", "Input",
                WorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(), null, null, null,
                NOW, NOW, null, List.of(REPOSITORY_ID));
    }
}
