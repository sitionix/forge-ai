package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataNodeRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataConnectionResolutionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunExecutionEdgeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresWorkflowRunRepositoryTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TASK_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID NODE_RUN_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID NODE_RUN_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID SOURCE_NODE_A = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE_NODE_B = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID FRAME_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_PORT_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID OUTPUT_PORT_ID = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID CONNECTION_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Mock
    private SpringDataWorkflowRunRepository workflowRunRepository;
    @Mock
    private SpringDataNodeRunRepository nodeRunRepository;
    @Mock
    private SpringDataConnectionResolutionRepository resolutionRepository;
    @Mock
    private SpringDataWorkflowRunExecutionEdgeRepository executionEdgeRepository;
    @Mock
    private WorkflowRunGraphRepository graphRepository;

    private PostgresWorkflowRunRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new PostgresWorkflowRunRepository(
                this.workflowRunRepository,
                this.nodeRunRepository,
                this.resolutionRepository,
                this.executionEdgeRepository,
                this.graphRepository
        );
        lenient().when(this.resolutionRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(RUN_ID)).thenReturn(List.of());
        lenient().when(this.executionEdgeRepository.findByWorkflowRunIdOrderBySourceNodeRunIdAscTargetNodeRunIdAsc(RUN_ID)).thenReturn(List.of());
        lenient().when(this.graphRepository.findByWorkflowRunId(RUN_ID)).thenReturn(new WorkflowRunGraph(RUN_ID, List.of(), List.of(), List.of()));
    }

    @Test
    void persistsWorkflowRunAndNodeRunSnapshotFields() {
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        this.repository.save(this.run(List.of(
                this.nodeRun(NODE_RUN_A, SOURCE_NODE_A, AGENT_A, null, null),
                this.nodeRun(NODE_RUN_B, SOURCE_NODE_B, AGENT_B, null, null)
        )));

        final WorkflowRunEntity savedRun = this.savedRun();
        assertThat(savedRun.getId()).isEqualTo(RUN_ID);
        assertThat(savedRun.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(savedRun.getSourceWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(savedRun.getTaskId()).isEqualTo(TASK_ID);
        assertThat(savedRun.getWorkflowName()).isEqualTo("Full Testing");
        assertThat(savedRun.getInput()).isEqualTo("Review auth changes.");
        assertThat(savedRun.getStatus()).isEqualTo("QUEUED");
        assertThat(savedRun.getCreatedAt()).isEqualTo(NOW);
        assertThat(savedRun.getStartedAt()).isNull();
        assertThat(savedRun.getFinishedAt()).isNull();

        final List<NodeRunEntity> savedNodes = this.savedNodes();
        assertThat(savedNodes).hasSize(2);
        assertThat(savedNodes).allSatisfy(node -> assertThat(node.getWorkflowRunId()).isEqualTo(RUN_ID));
        final NodeRunEntity second = savedNodes.stream()
                .filter(node -> NODE_RUN_B.equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(second.getSourceNodeId()).isEqualTo(SOURCE_NODE_B);
        assertThat(second.getSourceAgentId()).isEqualTo(AGENT_B);
        assertThat(second.getAgentName()).isEqualTo("Agent " + AGENT_B);
        assertThat(second.getAgentInstructions()).isEqualTo("Instructions " + AGENT_B);
        assertThat(second.getAgentOutputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(second.getExecutionFrameId()).isEqualTo(FRAME_ID);
        assertThat(second.getEnteredViaInputPortId()).isNull();
        assertThat(second.getActivationFrameId()).isNull();
        assertThat(second.getSelectedOutputPortId()).isNull();
        assertThat(second.getPositionX()).isEqualTo(3.0);
        assertThat(second.getPositionY()).isEqualTo(4.0);
        assertThat(second.getStatus()).isEqualTo("PENDING");
        assertThat(second.getOutput()).isNull();
        assertThat(second.getFailureCode()).isNull();
        assertThat(second.getFailureMessage()).isNull();
        assertThat(second.getStartedAt()).isNull();
        assertThat(second.getFinishedAt()).isNull();
        verify(this.graphRepository, never()).findByWorkflowRunId(any());
    }

    @Test
    void saveReturnsInMemoryRuntimeGraphWithoutReadingSnapshotRepository() {
        final WorkflowRunGraph graph = new WorkflowRunGraph(
                RUN_ID,
                List.of(new RunNode(
                        RUN_ID,
                        SOURCE_NODE_A,
                        AGENT_A,
                        "Planner",
                        "Use the task input.",
                        AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                        new NodeRunExecutionModel("codex", "model-a", "medium"),
                        com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                        new NodePosition(10.0, 20.0)
                )),
                List.of(new RunPort(RUN_ID, OUTPUT_PORT_ID, SOURCE_NODE_A, PortDirection.OUTPUT, "Done", "Terminal output.", 0)),
                List.of()
        );
        final WorkflowRun source = new WorkflowRun(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                TASK_ID,
                "Full Testing",
                "Review auth changes.",
                WorkflowRunStatus.QUEUED,
                List.of(this.nodeRun(NODE_RUN_A, SOURCE_NODE_A, AGENT_A, null, null)),
                List.of(),
                List.of(),
                graph,
                NOW,
                null,
                null
        );
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun saved = this.repository.save(source);

        assertThat(saved.runtimeGraph()).isSameAs(graph);
        assertThat(saved.nodeRuns()).hasSize(1);
        verify(this.graphRepository, never()).findByWorkflowRunId(any());
    }

    @Test
    void findByIdReconstructsDomainIncludingNodeRunsOutputAndFailure() {
        final NodeRunEntity node = this.nodeEntity(NODE_RUN_B, SOURCE_NODE_B, AGENT_B);
        node.setOutput("{\"summary\":\"done\"}");
        node.setFailureCode("ERR");
        node.setFailureMessage("Failed");
        node.setExecutionModelProviderId("codex");
        node.setExecutionModelId("model-b");
        node.setExecutionModelEffortId("xhigh");
        when(this.workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(this.runEntity(RUN_ID, NOW)));
        when(this.nodeRunRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(RUN_ID)).thenReturn(List.of(node));

        final WorkflowRun run = this.repository.findById(RUN_ID).orElseThrow();

        assertThat(run.id()).isEqualTo(RUN_ID);
        assertThat(run.taskId()).isEqualTo(TASK_ID);
        assertThat(run.runtimeGraph()).isNull();
        assertThat(run.nodeRuns()).singleElement().satisfies(nodeRun -> {
            assertThat(nodeRun.id()).isEqualTo(NODE_RUN_B);
            assertThat(nodeRun.workflowRunId()).isEqualTo(RUN_ID);
            assertThat(nodeRun.executionFrameId()).isEqualTo(FRAME_ID);
            assertThat(nodeRun.enteredViaInputPortId()).isNull();
            assertThat(nodeRun.activationFrameId()).isNull();
            assertThat(nodeRun.position()).isEqualTo(new NodePosition(3.0, 4.0));
            assertThat(nodeRun.output()).isEqualTo(new NodeRunOutput("{\"summary\":\"done\"}"));
            assertThat(nodeRun.failure()).isEqualTo(new NodeRunFailure("ERR", "Failed"));
            assertThat(nodeRun.executionModel()).isEqualTo(new NodeRunExecutionModel("codex", "model-b", "xhigh"));
        });
        verify(this.nodeRunRepository).findByWorkflowRunIdOrderByCreatedAtAscIdAsc(RUN_ID);
        verify(this.graphRepository).findByWorkflowRunId(RUN_ID);
    }

    @Test
    void findByIdIncludesRuntimeGraphSnapshotWhenPresent() {
        final WorkflowRunGraph graph = new WorkflowRunGraph(
                RUN_ID,
                List.of(new RunNode(
                        RUN_ID,
                        SOURCE_NODE_A,
                        AGENT_A,
                        "Planner",
                        "Use the task input.",
                        AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                        new NodeRunExecutionModel("codex", "model-a", "medium"),
                        com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                        new NodePosition(10.0, 20.0)
                )),
                List.of(
                        new RunPort(RUN_ID, INPUT_PORT_ID, SOURCE_NODE_A, PortDirection.INPUT, "Initial", "Root input.", 0),
                        new RunPort(RUN_ID, OUTPUT_PORT_ID, SOURCE_NODE_A, PortDirection.OUTPUT, "Done", "Terminal output.", 0)
                ),
                List.of(new RunConnection(RUN_ID, CONNECTION_ID, OUTPUT_PORT_ID, INPUT_PORT_ID))
        );
        when(this.workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(this.runEntity(RUN_ID, NOW)));
        when(this.nodeRunRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(RUN_ID)).thenReturn(List.of());
        when(this.graphRepository.findByWorkflowRunId(RUN_ID)).thenReturn(graph);

        final WorkflowRun run = this.repository.findById(RUN_ID).orElseThrow();

        assertThat(run.runtimeGraph()).isEqualTo(graph);
    }

    @Test
    void findByIdReconstructsExecutionModelWithNullableEffort() {
        final NodeRunEntity node = this.nodeEntity(NODE_RUN_A, SOURCE_NODE_A, AGENT_A);
        node.setExecutionModelProviderId("codex");
        node.setExecutionModelId("model-without-effort");
        node.setExecutionModelEffortId(null);
        when(this.workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(this.runEntity(RUN_ID, NOW)));
        when(this.nodeRunRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(RUN_ID)).thenReturn(List.of(node));

        final WorkflowRun run = this.repository.findById(RUN_ID).orElseThrow();

        assertThat(run.nodeRuns()).singleElement()
                .extracting(NodeRun::executionModel)
                .isEqualTo(new NodeRunExecutionModel("codex", "model-without-effort", null));
    }

    @Test
    void savePersistsExecutionModelWithNullableEffort() {
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        this.repository.save(this.run(List.of(this.withExecutionModel(
                this.nodeRun(NODE_RUN_A, SOURCE_NODE_A, AGENT_A, null, null),
                new NodeRunExecutionModel("codex", "model-without-effort", null)
        ))));

        assertThat(this.savedNodes()).singleElement().satisfies(node -> {
            assertThat(node.getExecutionModelProviderId()).isEqualTo("codex");
            assertThat(node.getExecutionModelId()).isEqualTo("model-without-effort");
            assertThat(node.getExecutionModelEffortId()).isNull();
        });
    }

    @Test
    void historyUsesDeterministicRepositoryOrderingAndDoesNotLoadNodeRuns() {
        final UUID olderRunId = UUID.fromString("33333333-3333-4333-8333-333333333332");
        when(this.workflowRunRepository.findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(WORKFLOW_ID)).thenReturn(List.of(
                this.runEntity(RUN_ID, Instant.parse("2026-08-10T12:01:00Z")),
                this.runEntity(olderRunId, Instant.parse("2026-08-10T12:00:00Z"))
        ));

        final List<WorkflowRunSummary> runs = this.repository.findSummariesBySourceWorkflowId(WORKFLOW_ID);

        assertThat(runs).extracting(WorkflowRunSummary::id).containsExactly(RUN_ID, olderRunId);
        assertThat(runs).first().satisfies(summary -> {
            assertThat(summary.sourceWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(summary.taskId()).isEqualTo(TASK_ID);
            assertThat(summary.workflowName()).isEqualTo("Full Testing");
            assertThat(summary.status()).isEqualTo(WorkflowRunStatus.QUEUED);
            assertThat(summary.createdAt()).isEqualTo(Instant.parse("2026-08-10T12:01:00Z"));
            assertThat(summary.startedAt()).isNull();
            assertThat(summary.finishedAt()).isNull();
        });
        verify(this.workflowRunRepository).findBySourceWorkflowIdOrderByCreatedAtDescIdDesc(WORKFLOW_ID);
        verifyNoInteractions(this.nodeRunRepository);
    }

    @Test
    void taskHistoryUsesDeterministicRepositoryOrderingAndDoesNotLoadNodeRuns() {
        final UUID olderRunId = UUID.fromString("33333333-3333-4333-8333-333333333332");
        when(this.workflowRunRepository.findByTaskIdOrderByCreatedAtDescIdDesc(TASK_ID)).thenReturn(List.of(
                this.runEntity(RUN_ID, Instant.parse("2026-08-10T12:01:00Z")),
                this.runEntity(olderRunId, Instant.parse("2026-08-10T12:00:00Z"))
        ));

        final List<WorkflowRunSummary> runs = this.repository.findSummariesByTaskId(TASK_ID);

        assertThat(runs).extracting(WorkflowRunSummary::id).containsExactly(RUN_ID, olderRunId);
        assertThat(runs).extracting(WorkflowRunSummary::taskId).containsOnly(TASK_ID);
        verify(this.workflowRunRepository).findByTaskIdOrderByCreatedAtDescIdDesc(TASK_ID);
        verifyNoInteractions(this.nodeRunRepository);
    }

    private WorkflowRun run(final List<NodeRun> nodeRuns) {
        return new WorkflowRun(RUN_ID, PROJECT_ID, WORKFLOW_ID, TASK_ID, "Full Testing", "Review auth changes.", WorkflowRunStatus.QUEUED, nodeRuns, NOW, null, null);
    }

    private NodeRun nodeRun(final UUID id,
                            final UUID sourceNodeId,
                            final UUID sourceAgentId,
                            final NodeRunOutput output,
                            final NodeRunFailure failure) {
        final double x = NODE_RUN_A.equals(id) ? 1.0 : 3.0;
        final double y = NODE_RUN_A.equals(id) ? 2.0 : 4.0;
        return new NodeRun(
                id,
                RUN_ID,
                sourceNodeId,
                sourceAgentId,
                "Agent " + sourceAgentId,
                "Instructions " + sourceAgentId,
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(x, y),
                FRAME_ID,
                null,
                null,
                null,
                NodeRunStatus.PENDING,
                output,
                failure,
                null,
                NOW,
                null,
                null
        );
    }

    private NodeRun withExecutionModel(final NodeRun nodeRun, final NodeRunExecutionModel executionModel) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.inputMode(),
                nodeRun.position(),
                nodeRun.executionFrameId(),
                nodeRun.enteredViaInputPortId(),
                nodeRun.activationFrameId(),
                nodeRun.selectedOutputPortId(),
                nodeRun.status(),
                nodeRun.output(),
                nodeRun.failure(),
                executionModel,
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt()
        );
    }

    private WorkflowRunEntity runEntity(final UUID runId, final Instant createdAt) {
        final WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setId(runId);
        entity.setProjectId(PROJECT_ID);
        entity.setSourceWorkflowId(WORKFLOW_ID);
        entity.setTaskId(TASK_ID);
        entity.setWorkflowName("Full Testing");
        entity.setInput("Review auth changes.");
        entity.setStatus("QUEUED");
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private NodeRunEntity nodeEntity(final UUID id,
                                     final UUID sourceNodeId,
                                     final UUID sourceAgentId) {
        final NodeRunEntity entity = new NodeRunEntity();
        entity.setId(id);
        entity.setWorkflowRunId(RUN_ID);
        entity.setSourceNodeId(sourceNodeId);
        entity.setSourceAgentId(sourceAgentId);
        entity.setAgentName("Agent " + sourceAgentId);
        entity.setAgentInstructions("Instructions " + sourceAgentId);
        entity.setAgentOutputSchema("{\"type\":\"object\"}");
        entity.setInputMode("DEPENDENCIES_ONLY");
        entity.setExecutionFrameId(FRAME_ID);
        entity.setEnteredViaInputPortId(null);
        entity.setActivationFrameId(null);
        entity.setSelectedOutputPortId(null);
        entity.setPositionX(3.0);
        entity.setPositionY(4.0);
        entity.setStatus("PENDING");
        entity.setCreatedAt(NOW);
        return entity;
    }

    private WorkflowRunEntity savedRun() {
        final ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
        verify(this.workflowRunRepository).save(captor.capture());
        return captor.getValue();
    }

    private List<NodeRunEntity> savedNodes() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<NodeRunEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(this.nodeRunRepository).saveAll(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).toList();
    }
}
