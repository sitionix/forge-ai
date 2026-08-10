package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowRunUseCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final AgentOutputSchema SCHEMA_A = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\",\"title\":\"A\"}");
    private static final AgentOutputSchema SCHEMA_B = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\",\"title\":\"B\"}");
    private static final AgentOutputSchema SCHEMA_C = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\",\"title\":\"C\"}");

    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID workflowId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID nodeA = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID nodeB = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID nodeC = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final UUID nodeD = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private final UUID agentA = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private final UUID agentB = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    private final UUID agentC = UUID.fromString("a1000000-0000-4000-8000-000000000003");

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private AgentDefinitionRepository agentDefinitionRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;

    private WorkflowRunUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new WorkflowRunUseCases(
                this.workflowRepository,
                this.agentDefinitionRepository,
                this.workflowRunRepository,
                CLOCK
        );
    }

    @Test
    void createsAtomicWorkflowSnapshotWithTranslatedDependencies() {
        final Workflow workflow = this.fanInWorkflow();
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow), Optional.of(workflow));
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(
                this.agent(this.agentA, "Analyzer", "Analyze v1", SCHEMA_A, this.projectId),
                this.agent(this.agentB, "Builder", "Build v1", SCHEMA_B, this.projectId),
                this.agent(this.agentC, "Reviewer", "Review v1", SCHEMA_C, this.projectId)
        ));
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun run = this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("  Review auth changes.  "));

        assertThat(run.id()).isNotNull();
        assertThat(run.projectId()).isEqualTo(this.projectId);
        assertThat(run.sourceWorkflowId()).isEqualTo(this.workflowId);
        assertThat(run.workflowName()).isEqualTo("Full Testing");
        assertThat(run.input()).isEqualTo("Review auth changes.");
        assertThat(run.status()).isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(run.createdAt()).isEqualTo(NOW);
        assertThat(run.startedAt()).isNull();
        assertThat(run.finishedAt()).isNull();
        assertThat(run.nodeRuns()).hasSize(4);
        assertThat(run.nodeRuns()).extracting(NodeRun::id).doesNotHaveDuplicates();
        assertThat(run.nodeRuns()).extracting(NodeRun::sourceNodeId)
                .containsExactly(this.nodeA, this.nodeB, this.nodeC, this.nodeD);
        assertThat(run.nodeRuns()).allSatisfy(nodeRun -> {
            assertThat(nodeRun.status()).isEqualTo(NodeRunStatus.PENDING);
            assertThat(nodeRun.createdAt()).isEqualTo(NOW);
            assertThat(nodeRun.startedAt()).isNull();
            assertThat(nodeRun.finishedAt()).isNull();
            assertThat(nodeRun.output()).isNull();
            assertThat(nodeRun.failure()).isNull();
        });
        assertThat(this.nodeRun(run, this.nodeA).agentName()).isEqualTo("Analyzer");
        assertThat(this.nodeRun(run, this.nodeA).agentInstructions()).isEqualTo("Analyze v1");
        assertThat(this.nodeRun(run, this.nodeA).agentOutputSchema()).isEqualTo(SCHEMA_A);
        assertThat(this.nodeRun(run, this.nodeD).position()).isEqualTo(new NodePosition(70.0, 80.0));

        final Map<UUID, UUID> runtimeIdBySourceNodeId = run.nodeRuns().stream()
                .collect(Collectors.toMap(NodeRun::sourceNodeId, NodeRun::id));
        assertThat(this.nodeRun(run, this.nodeA).dependsOnNodeRunIds()).isEmpty();
        assertThat(this.nodeRun(run, this.nodeB).dependsOnNodeRunIds()).containsExactly(runtimeIdBySourceNodeId.get(this.nodeA));
        assertThat(this.nodeRun(run, this.nodeC).dependsOnNodeRunIds()).containsExactly(runtimeIdBySourceNodeId.get(this.nodeA));
        assertThat(this.nodeRun(run, this.nodeD).dependsOnNodeRunIds())
                .containsExactly(runtimeIdBySourceNodeId.get(this.nodeB), runtimeIdBySourceNodeId.get(this.nodeC));
        assertThat(run.nodeRuns())
                .flatExtracting(NodeRun::dependsOnNodeRunIds)
                .doesNotContain(this.nodeA, this.nodeB, this.nodeC, this.nodeD);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<UUID>> agentIds = ArgumentCaptor.forClass(Collection.class);
        verify(this.agentDefinitionRepository).findByIds(agentIds.capture());
        assertThat(agentIds.getValue()).containsExactly(this.agentA, this.agentB, this.agentC);

        final InOrder order = org.mockito.Mockito.inOrder(this.workflowRepository, this.agentDefinitionRepository, this.workflowRunRepository);
        order.verify(this.workflowRepository).findById(this.workflowId);
        order.verify(this.workflowRepository).findByIdForUpdate(this.workflowId);
        order.verify(this.workflowRepository).findById(this.workflowId);
        order.verify(this.agentDefinitionRepository).findByIds(any());
        order.verify(this.workflowRunRepository).save(any());
    }

    @Test
    void batchLoadsUniqueAgentIdsButCreatesOneNodeRunPerNode() {
        final Workflow workflow = new Workflow(this.workflowId, this.projectId, "Repeated", "repeated", List.of(
                this.node(this.nodeA, this.agentA, List.of(), 1.0, 2.0),
                this.node(this.nodeB, this.agentA, List.of(this.nodeA), 3.0, 4.0)
        ), Instant.EPOCH, Instant.EPOCH);
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow), Optional.of(workflow));
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent(this.agentA, "Analyzer", "Analyze v1", SCHEMA_A, this.projectId)));
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun run = this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it"));

        assertThat(run.nodeRuns()).hasSize(2);
        assertThat(run.nodeRuns()).extracting(NodeRun::sourceAgentId).containsExactly(this.agentA, this.agentA);
        assertThat(run.nodeRuns()).extracting(NodeRun::id).doesNotHaveDuplicates();
        assertThat(run.nodeRuns()).extracting(NodeRun::sourceNodeId).containsExactly(this.nodeA, this.nodeB);
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<UUID>> agentIds = ArgumentCaptor.forClass(Collection.class);
        verify(this.agentDefinitionRepository).findByIds(agentIds.capture());
        assertThat(agentIds.getValue()).containsExactly(this.agentA);
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("  ")))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_WORKFLOW_RUN_INPUT");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void missingWorkflowReturnsControlledNotFound() {
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_NOT_FOUND");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void missingAgentSnapshotFailsClosed() {
        final Workflow workflow = this.fanInWorkflow();
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow), Optional.of(workflow));
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent(this.agentA, "Analyzer", "Analyze", SCHEMA_A, this.projectId)));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_RUN_SNAPSHOT_INVALID");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void crossProjectAgentSnapshotFailsClosed() {
        final Workflow workflow = new Workflow(this.workflowId, this.projectId, "Full Testing", "full testing", List.of(
                this.node(this.nodeA, this.agentA, List.of(), 1.0, 2.0)
        ), Instant.EPOCH, Instant.EPOCH);
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow), Optional.of(workflow));
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent(
                this.agentA,
                "Analyzer",
                "Analyze",
                SCHEMA_A,
                UUID.fromString("99999999-9999-4999-8999-999999999999")
        )));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_RUN_SNAPSHOT_INVALID");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void invalidPersistedDependencyFailsClosed() {
        final Workflow workflow = new Workflow(this.workflowId, this.projectId, "Full Testing", "full testing", List.of(
                this.node(this.nodeA, this.agentA, List.of(UUID.fromString("99999999-9999-4999-8999-999999999999")), 1.0, 2.0)
        ), Instant.EPOCH, Instant.EPOCH);
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow), Optional.of(workflow));
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent(this.agentA, "Analyzer", "Analyze", SCHEMA_A, this.projectId)));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_RUN_SNAPSHOT_INVALID");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void listDelegatesDeterministicRepositoryHistory() {
        final Workflow workflow = this.fanInWorkflow();
        final WorkflowRunSummary first = this.summary(UUID.fromString("50000000-0000-4000-8000-000000000001"), Instant.parse("2026-08-10T12:00:00Z"));
        final WorkflowRunSummary second = this.summary(UUID.fromString("50000000-0000-4000-8000-000000000002"), Instant.parse("2026-08-10T12:01:00Z"));
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.workflowRunRepository.findSummariesBySourceWorkflowId(this.workflowId)).thenReturn(List.of(second, first));

        assertThat(this.useCases.listWorkflowRuns(this.workflowId)).containsExactly(second, first);
        verify(this.workflowRunRepository).findSummariesBySourceWorkflowId(this.workflowId);
    }

    @Test
    void getReturnsExistingRunAndMissingRunReturnsControlledNotFound() {
        final UUID runId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        final WorkflowRun run = this.run(runId, NOW);
        when(this.workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThat(this.useCases.getWorkflowRun(runId)).isSameAs(run);

        final UUID missingRunId = UUID.fromString("50000000-0000-4000-8000-000000009999");
        when(this.workflowRunRepository.findById(missingRunId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> this.useCases.getWorkflowRun(missingRunId))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_RUN_NOT_FOUND");
    }

    private Workflow fanInWorkflow() {
        return new Workflow(this.workflowId, this.projectId, "Full Testing", "full testing", List.of(
                this.node(this.nodeA, this.agentA, List.of(), 10.0, 20.0),
                this.node(this.nodeB, this.agentB, List.of(this.nodeA), 30.0, 40.0),
                this.node(this.nodeC, this.agentA, List.of(this.nodeA), 50.0, 60.0),
                this.node(this.nodeD, this.agentC, List.of(this.nodeB, this.nodeC), 70.0, 80.0)
        ), Instant.EPOCH, Instant.EPOCH);
    }

    private WorkflowRun run(final UUID runId, final Instant createdAt) {
        return new WorkflowRun(runId, this.projectId, this.workflowId, "Full Testing", "Run it", WorkflowRunStatus.QUEUED, List.of(), createdAt, null, null);
    }

    private WorkflowRunSummary summary(final UUID runId, final Instant createdAt) {
        return new WorkflowRunSummary(runId, this.workflowId, "Full Testing", WorkflowRunStatus.QUEUED, createdAt, null, null);
    }

    private NodeRun nodeRun(final WorkflowRun run, final UUID sourceNodeId) {
        return run.nodeRuns().stream()
                .filter(nodeRun -> sourceNodeId.equals(nodeRun.sourceNodeId()))
                .findFirst()
                .orElseThrow();
    }

    private Node node(final UUID id, final UUID targetId, final List<UUID> dependsOnNodeIds, final double x, final double y) {
        return new Node(id, targetId, dependsOnNodeIds, new NodePosition(x, y));
    }

    private AgentDefinition agent(final UUID id,
                                  final String name,
                                  final String instructions,
                                  final AgentOutputSchema outputSchema,
                                  final UUID projectId) {
        return new AgentDefinition(id, projectId, name, name.toLowerCase(), instructions, outputSchema, Instant.EPOCH, Instant.EPOCH);
    }
}
