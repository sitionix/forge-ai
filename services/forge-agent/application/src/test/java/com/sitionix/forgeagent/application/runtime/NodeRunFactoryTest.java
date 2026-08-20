package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
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

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OUTSIDE_REPOSITORY_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private final NodeRunFactory factory = new NodeRunFactory(Clock.fixed(NOW, ZoneOffset.UTC));
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
        return new RunNode(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Agent", "Work.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                new NodeRunExecutionModel("codex", "model", null), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0), mode);
    }

    private WorkflowRun workflowRun() {
        return new WorkflowRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), null, "Workflow", "Input",
                WorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(), null, null, null,
                NOW, NOW, null, List.of(REPOSITORY_ID));
    }
}
