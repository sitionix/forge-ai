package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.runtime.NodeRunFactory;
import com.sitionix.forgeagent.application.runtime.ExecutionBudgetPolicy;
import com.sitionix.forgeagent.application.runtime.WorkflowEntrySelector;
import com.sitionix.forgeagent.application.runtime.WorkflowRunSnapshotBuilder;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowRunUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID workflowId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID taskId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID nodeId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID agentId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;
    @Mock
    private WorkflowRunGraphRepository graphRepository;
    @Mock
    private ExecutionFrameRepository executionFrameRepository;
    @Mock
    private NodeRunRepository nodeRunRepository;
    @Mock
    private AgentDefinitionRepository agentDefinitionRepository;
    @Mock
    private WorkflowEntrySelector entrySelector;
    @Mock
    private ExecutionBudgetPolicy executionBudgetPolicy;

    private WorkflowRunUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new WorkflowRunUseCases(
                this.workflowRepository,
                this.workflowRunRepository,
                this.graphRepository,
                this.executionFrameRepository,
                this.nodeRunRepository,
                new WorkflowRunSnapshotBuilder(this.agentDefinitionRepository),
                this.entrySelector,
                new NodeRunFactory(CLOCK),
                this.executionBudgetPolicy,
                CLOCK
        );
    }

    @Test
    void createWorkflowRunSnapshotsGraphAndCreatesOnlyRootPendingNodeRuns() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));
        when(this.entrySelector.selectEntries(any())).thenAnswer(invocation -> invocation.<com.sitionix.forgeagent.domain.model.WorkflowRunGraph>getArgument(0).nodes());
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.executionFrameRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun run = this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it"));

        assertThat(run.status()).isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(run.nodeRuns()).hasSize(1);
        assertThat(run.nodeRuns().get(0)).satisfies(nodeRun -> {
            assertThat(nodeRun.sourceNodeId()).isEqualTo(this.nodeId);
            assertThat(nodeRun.status()).isEqualTo(NodeRunStatus.PENDING);
            assertThat(nodeRun.executionFrameId()).isNotNull();
            assertThat(nodeRun.activationFrameId()).isNull();
            assertThat(nodeRun.enteredViaInputPortId()).isNull();
        });
        verify(this.graphRepository).saveSnapshot(any());
        verify(this.executionFrameRepository).save(any(ExecutionFrame.class));
    }

    @Test
    void taskTriggeredRunPreservesTaskId() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));
        when(this.entrySelector.selectEntries(any())).thenAnswer(invocation -> invocation.<com.sitionix.forgeagent.domain.model.WorkflowRunGraph>getArgument(0).nodes());
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.executionFrameRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun run = this.useCases.createWorkflowRunForTask(this.workflowId, new CreateWorkflowRunCommand("Run it"), this.taskId);

        assertThat(run.taskId()).isEqualTo(this.taskId);
    }

    @Test
    void eachWorkflowRunUsesTheLiveWorkflowShapeOnlyForItsOwnSnapshot() {
        final Workflow firstWorkflowShape = this.workflowWithOutput("Done", "Terminal output");
        final Workflow secondWorkflowShape = this.workflowWithOutput("Escalate", "Escalation output");
        when(this.workflowRepository.findByIdForUpdate(this.workflowId))
                .thenReturn(Optional.of(firstWorkflowShape), Optional.of(secondWorkflowShape));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));
        when(this.entrySelector.selectEntries(any())).thenAnswer(invocation -> invocation.<WorkflowRunGraph>getArgument(0).nodes());
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.executionFrameRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run one"));
        this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run two"));

        final ArgumentCaptor<WorkflowRunGraph> graphCaptor = ArgumentCaptor.forClass(WorkflowRunGraph.class);
        verify(this.graphRepository, times(2)).saveSnapshot(graphCaptor.capture());
        assertThat(graphCaptor.getAllValues().get(0).ports())
                .extracting(port -> port.name() + ":" + port.description())
                .contains("Done:Terminal output")
                .doesNotContain("Escalate:Escalation output");
        assertThat(graphCaptor.getAllValues().get(1).ports())
                .extracting(port -> port.name() + ":" + port.description())
                .contains("Escalate:Escalation output")
                .doesNotContain("Done:Terminal output");
    }

    @Test
    void missingEntryReturnsControlledError() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));
        when(this.entrySelector.selectEntries(any())).thenReturn(List.of());

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_ENTRY_NOT_FOUND");

        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("  ")))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_WORKFLOW_RUN_INPUT");
    }

    @Test
    void rejectsMissingTaskId() {
        assertThatThrownBy(() -> this.useCases.createWorkflowRunForTask(this.workflowId, new CreateWorkflowRunCommand("Run it"), null))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_WORKFLOW_RUN_TASK");
    }

    @Test
    void missingWorkflowReturnsControlledNotFound() {
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_NOT_FOUND");
    }

    @Test
    void listDelegatesDeterministicRepositoryHistory() {
        final Workflow workflow = this.workflow();
        final WorkflowRunSummary summary = new WorkflowRunSummary(
                UUID.randomUUID(),
                this.workflowId,
                this.taskId,
                "Full Testing",
                WorkflowRunStatus.QUEUED,
                NOW,
                null,
                null
        );
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.workflowRunRepository.findSummariesBySourceWorkflowId(this.workflowId)).thenReturn(List.of(summary));

        assertThat(this.useCases.listWorkflowRuns(this.workflowId)).containsExactly(summary);
    }

    private Workflow workflow() {
        return this.workflowWithOutput("Done", "Done");
    }

    private Workflow workflowWithOutput(final String outputName, final String outputDescription) {
        return new Workflow(
                this.workflowId,
                this.projectId,
                "Full Testing",
                "full testing",
                List.of(new Node(
                        this.nodeId,
                        this.agentId,
                        com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
                        List.of(new NodePort(UUID.randomUUID(), outputName, outputDescription, 0)),
                        new NodePosition(1.0, 2.0)
                )),
                List.of(),
                NOW,
                NOW
        );
    }

    private AgentDefinition agent() {
        return new AgentDefinition(
                this.agentId,
                this.projectId,
                "Snapshot Agent",
                "snapshot agent",
                "Snapshot instructions.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                new AgentModelSelection("codex", "gpt-5", null),
                NOW,
                NOW
        );
    }
}
