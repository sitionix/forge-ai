package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RootNodeInputContentPolicyTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID WORKFLOW_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID NODE_RUN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID NODE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID FRAME_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_PORT_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVATION_FRAME_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock
    private WorkflowRunGraphRepository graphRepository;

    @Test
    void rootIsIdentifiedByNullActivationFrameAndReceivesOriginalTaskThroughConfiguredInput() {
        final RootNodeInputContentPolicy policy = new RootNodeInputContentPolicy(this.graphRepository);
        final RunPort inputPort = new RunPort(WORKFLOW_RUN_ID, INPUT_PORT_ID, NODE_ID, PortDirection.INPUT, "Initial", "Initial task.", 0);
        final NodeRun root = this.nodeRun(null);
        final WorkflowRun workflowRun = this.workflowRun();
        when(this.graphRepository.findPort(WORKFLOW_RUN_ID, INPUT_PORT_ID)).thenReturn(Optional.of(inputPort));

        final NodeExecutionInputContent content = policy.assemble(new NodeInputContentContext(workflowRun, root, List.of()));

        assertThat(policy.supports(new NodeInputContentContext(workflowRun, root, List.of()))).isTrue();
        assertThat(content.envelope().originalTask()).isEqualTo("Review auth changes.");
        assertThat(content.envelope().entryInputPort()).isEqualTo(inputPort);
        assertThat(content.envelope().contributions()).isEmpty();
    }

    @Test
    void laterActivationThroughSameInputIsNotRoot() {
        final RootNodeInputContentPolicy policy = new RootNodeInputContentPolicy(this.graphRepository);

        assertThat(policy.supports(new NodeInputContentContext(this.workflowRun(), this.nodeRun(ACTIVATION_FRAME_ID), List.of()))).isFalse();
    }

    @Test
    void legacyRootWithoutEnteredInputReceivesOriginalTaskAndNullEntryInput() {
        final RootNodeInputContentPolicy policy = new RootNodeInputContentPolicy(this.graphRepository);
        final NodeRun legacyRoot = this.nodeRun(null, null);

        final NodeExecutionInputContent content = policy.assemble(new NodeInputContentContext(this.workflowRun(), legacyRoot, List.of()));

        assertThat(policy.supports(new NodeInputContentContext(this.workflowRun(), legacyRoot, List.of()))).isTrue();
        assertThat(content.envelope().originalTask()).isEqualTo("Review auth changes.");
        assertThat(content.envelope().entryInputPort()).isNull();
        assertThat(content.envelope().contributions()).isEmpty();
    }

    private WorkflowRun workflowRun() {
        return new WorkflowRun(
                WORKFLOW_RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                WorkflowRunStatus.RUNNING,
                List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                null,
                null,
                NOW,
                NOW,
                null,
                java.util.List.of()
        );
    }

    private NodeRun nodeRun(final UUID activationFrameId) {
        return this.nodeRun(INPUT_PORT_ID, activationFrameId);
    }

    private NodeRun nodeRun(final UUID enteredViaInputPortId, final UUID activationFrameId) {
        return new NodeRun(
                NODE_RUN_ID,
                WORKFLOW_RUN_ID,
                NODE_ID,
                AGENT_ID,
                "Planner",
                "Plan work.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(1.0, 2.0),
                FRAME_ID,
                enteredViaInputPortId,
                activationFrameId,
                null,
                null,
                NodeRunStatus.PENDING,
                null,
                null,
                new NodeRunExecutionModel("codex", "gpt-5", null),
                NOW,
                null,
                null,
                null
        );
    }
}
