package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
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
class DependenciesOnlyNodeInputContentPolicyTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID WORKFLOW_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID NODE_RUN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE_NODE_RUN_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID NODE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID FRAME_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_PORT_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVATION_FRAME_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID CONNECTION_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID RESOLUTION_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE_REPOSITORY_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock
    private WorkflowRunGraphRepository graphRepository;
    @Mock
    private NodeRunRepository nodeRunRepository;

    @Test
    void laterDependenciesOnlyActivationThroughTaskInputPortUsesOnlyDeliveredPayloads() {
        final DependenciesOnlyNodeInputContentPolicy policy = new DependenciesOnlyNodeInputContentPolicy(this.graphRepository, this.nodeRunRepository);
        final RunPort inputPort = new RunPort(WORKFLOW_RUN_ID, INPUT_PORT_ID, NODE_ID, PortDirection.INPUT, "Initial", "Initial task.", 0);
        final NodeRunOutput payload = new NodeRunOutput("{\"review\":\"retry\"}");
        final ConnectionResolution resolution = new ConnectionResolution(
                RESOLUTION_ID,
                WORKFLOW_RUN_ID,
                ACTIVATION_FRAME_ID,
                SOURCE_NODE_RUN_ID,
                CONNECTION_ID,
                INPUT_PORT_ID,
                ConnectionResolutionType.DELIVERED,
                payload,
                NODE_RUN_ID,
                NOW,
                null
        );
        when(this.graphRepository.findPort(WORKFLOW_RUN_ID, INPUT_PORT_ID)).thenReturn(Optional.of(inputPort));
        when(this.nodeRunRepository.findByIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(this.sourceNodeRun(SOURCE_REPOSITORY_ID)));

        final NodeExecutionInputContent content = policy.assemble(new NodeInputContentContext(this.workflowRun(), this.nodeRun(), List.of(resolution)));

        assertThat(content.envelope().originalTask()).isNull();
        assertThat(content.envelope().entryInputPort()).isEqualTo(inputPort);
        assertThat(content.envelope().contributions()).singleElement().satisfies(contribution -> {
            assertThat(contribution.sourceNodeRunId()).isEqualTo(SOURCE_NODE_RUN_ID);
            assertThat(contribution.sourceConnectionId()).isEqualTo(CONNECTION_ID);
            assertThat(contribution.payload()).isEqualTo(payload);
            assertThat(contribution.sourceRepositoryId()).isEqualTo(SOURCE_REPOSITORY_ID);
        });
    }

    @Test
    void globalSourceContributionKeepsNullRepositoryId() {
        final DependenciesOnlyNodeInputContentPolicy policy = new DependenciesOnlyNodeInputContentPolicy(this.graphRepository, this.nodeRunRepository);
        final RunPort inputPort = new RunPort(WORKFLOW_RUN_ID, INPUT_PORT_ID, NODE_ID, PortDirection.INPUT, "Initial", "Initial task.", 0);
        final ConnectionResolution resolution = new ConnectionResolution(
                RESOLUTION_ID,
                WORKFLOW_RUN_ID,
                ACTIVATION_FRAME_ID,
                SOURCE_NODE_RUN_ID,
                CONNECTION_ID,
                INPUT_PORT_ID,
                ConnectionResolutionType.DELIVERED,
                new NodeRunOutput("{\"review\":\"retry\"}"),
                NODE_RUN_ID,
                NOW,
                null
        );
        when(this.graphRepository.findPort(WORKFLOW_RUN_ID, INPUT_PORT_ID)).thenReturn(Optional.of(inputPort));
        when(this.nodeRunRepository.findByIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(this.sourceNodeRun(null)));

        final NodeExecutionInputContent content = policy.assemble(new NodeInputContentContext(this.workflowRun(), this.nodeRun(), List.of(resolution)));

        assertThat(content.envelope().contributions()).singleElement()
                .satisfies(contribution -> assertThat(contribution.sourceRepositoryId()).isNull());
    }

    private WorkflowRun workflowRun() {
        return new WorkflowRun(
                WORKFLOW_RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Original task.",
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

    private NodeRun nodeRun() {
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
                INPUT_PORT_ID,
                ACTIVATION_FRAME_ID,
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

    private NodeRun sourceNodeRun(final UUID repositoryId) {
        return new NodeRun(
                SOURCE_NODE_RUN_ID,
                WORKFLOW_RUN_ID,
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
                AGENT_ID,
                "Source",
                "Source work.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(3.0, 4.0),
                FRAME_ID,
                null,
                null,
                null,
                null,
                NodeRunStatus.SUCCEEDED,
                null,
                null,
                new NodeRunExecutionModel("codex", "gpt-5", null),
                NOW,
                NOW,
                NOW,
                repositoryId
        );
    }
}
