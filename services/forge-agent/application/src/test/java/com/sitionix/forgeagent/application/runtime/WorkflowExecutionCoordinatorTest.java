package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionCoordinatorTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID WORKFLOW_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID NODE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_PORT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID TASK_OUTPUT_PORT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OUTPUT_PORT_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-12T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AgentOutputSchema SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}");
    private static final NodeRunExecutionModel MODEL = new NodeRunExecutionModel("codex", "gpt-5", null);

    @Mock
    private WorkflowRunRepository workflowRunRepository;
    @Mock
    private WorkflowRunGraphRepository graphRepository;
    @Mock
    private NodeRunRepository nodeRunRepository;
    @Mock
    private WorkflowCompletionPolicy completionPolicy;

    private WorkflowExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        this.coordinator = new WorkflowExecutionCoordinator(
                this.workflowRunRepository,
                this.graphRepository,
                this.nodeRunRepository,
                this.completionPolicy,
                CLOCK
        );
    }

    @Test
    void successfulDecisionPersistsLatestTaskOutputEmissionAsWorkflowResult() {
        final NodeRun older = this.nodeRun(
                UUID.fromString("60000000-0000-4000-8000-000000000001"),
                TASK_OUTPUT_PORT_ID,
                NOW.minusSeconds(10),
                new NodeRunOutput("{\"value\":\"older\"}"),
                NodeRunStatus.SUCCEEDED,
                NOW.minusSeconds(1)
        );
        final NodeRun latestById = this.nodeRun(
                UUID.fromString("60000000-0000-4000-8000-000000000003"),
                TASK_OUTPUT_PORT_ID,
                NOW,
                new NodeRunOutput("{\"value\":\"latest\"}"),
                NodeRunStatus.SUCCEEDED,
                NOW.minusSeconds(1)
        );
        final NodeRun earlierSameCreatedAt = this.nodeRun(
                UUID.fromString("60000000-0000-4000-8000-000000000002"),
                TASK_OUTPUT_PORT_ID,
                NOW,
                new NodeRunOutput("{\"value\":\"same-time-earlier-id\"}"),
                NodeRunStatus.SUCCEEDED,
                NOW.minusSeconds(1)
        );
        final NodeRun wrongOutput = this.nodeRun(
                UUID.fromString("60000000-0000-4000-8000-000000000004"),
                OTHER_OUTPUT_PORT_ID,
                NOW.plusSeconds(1),
                new NodeRunOutput("{\"value\":\"wrong-output\"}"),
                NodeRunStatus.SUCCEEDED,
                NOW
        );
        final NodeRun routingIncomplete = this.nodeRun(
                UUID.fromString("60000000-0000-4000-8000-000000000005"),
                TASK_OUTPUT_PORT_ID,
                NOW.plusSeconds(2),
                new NodeRunOutput("{\"value\":\"routing-incomplete\"}"),
                NodeRunStatus.SUCCEEDED,
                null
        );
        when(this.nodeRunRepository.findByWorkflowRunId(WORKFLOW_RUN_ID))
                .thenReturn(List.of(older, latestById, earlierSameCreatedAt, wrongOutput, routingIncomplete));

        this.coordinator.completionDecisionHandler(this.workflowRun(this.graph(TASK_OUTPUT_PORT_ID)))
                .handle(new SuccessfulWorkflowDecision());

        final WorkflowRun saved = this.savedWorkflowRun();
        assertThat(saved.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(saved.result()).isEqualTo(new NodeRunOutput("{\"value\":\"latest\"}"));
        assertThat(saved.resultSourceNodeRunId()).isEqualTo(latestById.id());
        assertThat(saved.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void successfulDecisionWithoutTaskOutputEmissionFailsConfiguredRun() {
        when(this.nodeRunRepository.findByWorkflowRunId(WORKFLOW_RUN_ID)).thenReturn(List.of(
                this.nodeRun(
                        UUID.fromString("60000000-0000-4000-8000-000000000001"),
                        OTHER_OUTPUT_PORT_ID,
                        NOW,
                        new NodeRunOutput("{\"value\":\"wrong-output\"}"),
                        NodeRunStatus.SUCCEEDED,
                        NOW
                )
        ));

        this.coordinator.completionDecisionHandler(this.workflowRun(this.graph(TASK_OUTPUT_PORT_ID)))
                .handle(new SuccessfulWorkflowDecision());

        final WorkflowRun saved = this.savedWorkflowRun();
        assertThat(saved.status()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(saved.result()).isNull();
        assertThat(saved.resultSourceNodeRunId()).isNull();
    }

    @Test
    void legacyRunWithoutTaskOutputSnapshotKeepsSuccessfulNullResultBehavior() {
        this.coordinator.completionDecisionHandler(this.workflowRun(this.graph(null)))
                .handle(new SuccessfulWorkflowDecision());

        final WorkflowRun saved = this.savedWorkflowRun();
        assertThat(saved.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(saved.result()).isNull();
        assertThat(saved.resultSourceNodeRunId()).isNull();
    }

    @Test
    void loadsSnapshottedGraphForLightweightLifecycleRun() {
        when(this.graphRepository.findByWorkflowRunId(WORKFLOW_RUN_ID)).thenReturn(this.graph(TASK_OUTPUT_PORT_ID));
        final NodeRun selected = this.nodeRun(
                UUID.fromString("60000000-0000-4000-8000-000000000001"),
                TASK_OUTPUT_PORT_ID,
                NOW,
                new NodeRunOutput("{\"value\":\"snapshot\"}"),
                NodeRunStatus.SUCCEEDED,
                NOW
        );
        when(this.nodeRunRepository.findByWorkflowRunId(WORKFLOW_RUN_ID)).thenReturn(List.of(selected));

        this.coordinator.completionDecisionHandler(this.workflowRun(null))
                .handle(new SuccessfulWorkflowDecision());

        final WorkflowRun saved = this.savedWorkflowRun();
        assertThat(saved.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(saved.runtimeGraph()).isEqualTo(this.graph(TASK_OUTPUT_PORT_ID));
        assertThat(saved.result()).isEqualTo(new NodeRunOutput("{\"value\":\"snapshot\"}"));
        assertThat(saved.resultSourceNodeRunId()).isEqualTo(selected.id());
    }

    private WorkflowRun savedWorkflowRun() {
        final ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(this.workflowRunRepository).saveLifecycle(captor.capture());
        return captor.getValue();
    }

    private WorkflowRun workflowRun(final WorkflowRunGraph graph) {
        return new WorkflowRun(
                WORKFLOW_RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Run it",
                WorkflowRunStatus.RUNNING,
                List.of(),
                List.of(),
                List.of(),
                graph,
                null,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(30),
                null
        );
    }

    private WorkflowRunGraph graph(final UUID taskOutputPortId) {
        return new WorkflowRunGraph(WORKFLOW_RUN_ID, INPUT_PORT_ID, taskOutputPortId, List.of(), List.of(), List.of());
    }

    private NodeRun nodeRun(final UUID id,
                            final UUID selectedOutputPortId,
                            final Instant createdAt,
                            final NodeRunOutput output,
                            final NodeRunStatus status,
                            final Instant routingCompletedAt) {
        return new NodeRun(
                id,
                WORKFLOW_RUN_ID,
                NODE_ID,
                AGENT_ID,
                "Agent",
                "Instructions",
                SCHEMA,
                NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0),
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                INPUT_PORT_ID,
                null,
                selectedOutputPortId,
                routingCompletedAt,
                status,
                output,
                null,
                MODEL,
                createdAt,
                createdAt,
                status == NodeRunStatus.SUCCEEDED ? createdAt.plusSeconds(1) : null
        );
    }
}
