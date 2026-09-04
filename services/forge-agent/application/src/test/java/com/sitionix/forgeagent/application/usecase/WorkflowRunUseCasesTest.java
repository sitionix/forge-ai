package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.runtime.ExecutionBudgetPolicy;
import com.sitionix.forgeagent.application.runtime.NodeRunFactory;
import com.sitionix.forgeagent.application.runtime.ScopeProjectionPolicy;
import com.sitionix.forgeagent.application.runtime.WorkflowRunSnapshotBuilder;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities;
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
    private final UUID secondNodeId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final UUID agentId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID secondAgentId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private final UUID inputPortId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID outputPortId = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID secondInputPortId = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID secondOutputPortId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID terminalOutputPortId = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private final UUID connectionId = UUID.fromString("99999999-9999-4999-8999-999999999999");

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
    private AgentExecutionProviderCapabilities providerCapabilities;
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
                new WorkflowRunSnapshotBuilder(this.agentDefinitionRepository, this.providerCapabilities),
                new NodeRunFactory(CLOCK, new ScopeProjectionPolicy()),
                this.executionBudgetPolicy,
                new ScopeProjectionPolicy(),
                CLOCK
        );
    }

    @Test
    void continuedContextOnUnsupportedProviderFailsBeforeRuntimePersistence() {
        final Workflow base = this.workflow();
        final Node continued = new Node(base.nodes().getFirst().id(), base.nodes().getFirst().targetId(),
                base.nodes().getFirst().inputMode(), base.nodes().getFirst().inputs(), base.nodes().getFirst().outputs(),
                base.nodes().getFirst().position(), base.nodes().getFirst().scopeMode(),
                NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE);
        final Workflow workflow = new Workflow(base.id(), base.projectId(), base.name(), base.normalizedName(),
                List.of(continued), base.connections(), base.taskInputPortId(), base.taskOutputPortId(),
                base.createdAt(), base.updatedAt());
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(
                this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("AGENT_CONTEXT_MODE_UNSUPPORTED");
        verify(this.workflowRunRepository, never()).save(any());
        verify(this.graphRepository, never()).saveSnapshot(any());
        verify(this.nodeRunRepository, never()).save(any());
    }

    @Test
    void createWorkflowRunSnapshotsGraphAndCreatesOnlyRootPendingNodeRuns() {
        final Workflow workflow = this.workflowWithTwoNodes();
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent(), this.secondAgent()));
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.executionFrameRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun run = this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it"));

        assertThat(run.status()).isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(run.runtimeGraph()).isNotNull();
        assertThat(run.runtimeGraph().taskInputPortId()).isEqualTo(this.inputPortId);
        assertThat(run.runtimeGraph().taskOutputPortId()).isEqualTo(this.terminalOutputPortId);
        assertThat(run.runtimeGraph().nodes())
                .extracting(item -> item.sourceNodeId())
                .containsExactly(this.nodeId, this.secondNodeId);
        assertThat(run.runtimeGraph().ports())
                .extracting(port -> port.sourcePortId() + ":" + port.name() + ":" + port.description())
                .contains(
                        this.inputPortId + ":Input:Input",
                        this.outputPortId + ":Done:Done",
                        this.secondInputPortId + ":Review feedback:Review feedback",
                        this.secondOutputPortId + ":Approved:Approved",
                        this.terminalOutputPortId + ":Task Result:Task Result"
                );
        assertThat(run.runtimeGraph().connections())
                .extracting(connection -> connection.sourceConnectionId())
                .containsExactly(this.connectionId, UUID.fromString("99999999-9999-4999-8999-999999999998"));
        assertThat(run.nodeRuns()).hasSize(1);
        assertThat(run.repositoryIds()).isEmpty();
        assertThat(run.nodeRuns().get(0)).satisfies(nodeRun -> {
            assertThat(nodeRun.sourceNodeId()).isEqualTo(this.nodeId);
            assertThat(nodeRun.status()).isEqualTo(NodeRunStatus.PENDING);
            assertThat(nodeRun.executionFrameId()).isNotNull();
            assertThat(nodeRun.activationFrameId()).isNull();
            assertThat(nodeRun.enteredViaInputPortId()).isEqualTo(this.inputPortId);
            assertThat(nodeRun.repositoryId()).isNull();
        });
        final ArgumentCaptor<WorkflowRun> savedRunCaptor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(this.workflowRunRepository).save(savedRunCaptor.capture());
        assertThat(savedRunCaptor.getValue().runtimeGraph()).isEqualTo(run.runtimeGraph());
        verify(this.graphRepository).saveSnapshot(any());
        verify(this.executionFrameRepository).save(any(ExecutionFrame.class));
    }

    @Test
    void taskTriggeredRunPreservesTaskId() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.executionFrameRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun run = this.useCases.createWorkflowRunForTask(
                this.workflowId, new CreateWorkflowRunCommand("Run it"), this.taskId, List.of(UUID.randomUUID()));

        assertThat(run.taskId()).isEqualTo(this.taskId);
        assertThat(run.runtimeGraph()).isNotNull();
        assertThat(run.runtimeGraph().nodes()).hasSize(1);
    }

    @Test
    void directRunRejectsAnyPerScopeNodeEvenWhenItIsNotTheRoot() {
        final Workflow base = this.workflowWithTwoNodes();
        final List<Node> nodes = List.of(base.nodes().getFirst(), this.withScope(base.nodes().get(1), NodeScopeMode.PER_SCOPE));
        final Workflow workflow = new Workflow(base.id(), base.projectId(), base.name(), base.normalizedName(), nodes,
                base.connections(), base.taskInputPortId(), base.taskOutputPortId(), base.createdAt(), base.updatedAt());
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent(), this.secondAgent()));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(
                this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("PER_SCOPE_RUN_REQUIRES_REPOSITORIES");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void perScopeTaskRootCreatesOneNodeRunPerRepositoryInSnapshotOrder() {
        final UUID repositoryA = UUID.fromString("77777777-7777-4777-8777-777777777771");
        final UUID repositoryB = UUID.fromString("77777777-7777-4777-8777-777777777772");
        final Workflow base = this.workflowWithTwoNodes();
        final Workflow workflow = new Workflow(base.id(), base.projectId(), base.name(), base.normalizedName(),
                List.of(this.withScope(base.nodes().getFirst(), NodeScopeMode.PER_SCOPE), base.nodes().get(1)), base.connections(),
                base.taskInputPortId(), base.taskOutputPortId(), base.createdAt(), base.updatedAt());
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent(), this.secondAgent()));
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.executionFrameRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun run = this.useCases.createWorkflowRunForTask(this.workflowId,
                new CreateWorkflowRunCommand("Run it"), this.taskId, List.of(repositoryA, repositoryB));

        assertThat(run.repositoryIds()).containsExactly(repositoryA, repositoryB);
        assertThat(run.nodeRuns()).extracting(NodeRun::repositoryId).containsExactly(repositoryA, repositoryB);
    }

    @Test
    void eachWorkflowRunUsesTheLiveWorkflowShapeOnlyForItsOwnSnapshot() {
        final Workflow firstWorkflowShape = this.workflowWithTaskInput(this.inputPortId, "Done", "Terminal output");
        final Workflow secondWorkflowShape = this.workflowWithTaskInput(this.secondInputPortId, "Escalate", "Escalation output");
        when(this.workflowRepository.findByIdForUpdate(this.workflowId))
                .thenReturn(Optional.of(firstWorkflowShape), Optional.of(secondWorkflowShape));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));
        when(this.workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.executionFrameRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final WorkflowRun firstRun = this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run one"));
        final WorkflowRun secondRun = this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run two"));

        final ArgumentCaptor<WorkflowRunGraph> graphCaptor = ArgumentCaptor.forClass(WorkflowRunGraph.class);
        verify(this.graphRepository, times(2)).saveSnapshot(graphCaptor.capture());
        assertThat(graphCaptor.getAllValues().get(0).ports())
                .extracting(port -> port.name() + ":" + port.description())
                .contains("Done:Terminal output")
                .doesNotContain("Escalate:Escalation output");
        assertThat(graphCaptor.getAllValues().get(0).taskInputPortId()).isEqualTo(this.inputPortId);
        assertThat(graphCaptor.getAllValues().get(0).taskOutputPortId()).isEqualTo(this.outputPortId);
        assertThat(graphCaptor.getAllValues().get(1).ports())
                .extracting(port -> port.name() + ":" + port.description())
                .contains("Escalate:Escalation output")
                .doesNotContain("Done:Terminal output");
        assertThat(graphCaptor.getAllValues().get(1).taskInputPortId()).isEqualTo(this.secondInputPortId);
        assertThat(graphCaptor.getAllValues().get(1).taskOutputPortId()).isEqualTo(this.secondOutputPortId);
        assertThat(firstRun.runtimeGraph()).isEqualTo(graphCaptor.getAllValues().get(0));
        assertThat(secondRun.runtimeGraph()).isEqualTo(graphCaptor.getAllValues().get(1));
    }

    @Test
    void missingTaskInputReturnsControlledError() {
        final Workflow workflow = new Workflow(
                this.workflowId,
                this.projectId,
                "Full Testing",
                "full testing",
                this.workflow().nodes(),
                List.of(),
                null,
                NOW,
                NOW
        );
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_TASK_INPUT_REQUIRED");

        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void missingTaskOutputReturnsControlledError() {
        final Workflow workflow = new Workflow(
                this.workflowId,
                this.projectId,
                "Full Testing",
                "full testing",
                this.workflow().nodes(),
                List.of(),
                this.inputPortId,
                NOW,
                NOW
        );
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_TASK_OUTPUT_REQUIRED");

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
        assertThatThrownBy(() -> this.useCases.createWorkflowRunForTask(
                this.workflowId, new CreateWorkflowRunCommand("Run it"), null, List.of(UUID.randomUUID())))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_WORKFLOW_RUN_TASK");
    }

    @Test
    void rejectsTaskRunWithEmptyRepositorySnapshotBeforePersistence() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));

        assertThatThrownBy(() -> this.useCases.createWorkflowRunForTask(
                this.workflowId, new CreateWorkflowRunCommand("Run it"), this.taskId, List.of()))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("TASK_RUN_REQUIRES_REPOSITORIES");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void rejectsTaskRunWithNullRepositorySnapshotThroughControlledValidation() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));

        assertThatThrownBy(() -> this.useCases.createWorkflowRunForTask(
                this.workflowId, new CreateWorkflowRunCommand("Run it"), this.taskId, null))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("TASK_RUN_REQUIRES_REPOSITORIES");
        verify(this.workflowRunRepository, never()).save(any());
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
        return this.workflowWithTaskInput(this.inputPortId, outputName, outputDescription);
    }

    private Workflow workflowWithTaskInput(final UUID taskInputPortId, final String outputName, final String outputDescription) {
        final UUID taskOutputPortId = this.inputPortId.equals(taskInputPortId) ? this.outputPortId : this.secondOutputPortId;
        return new Workflow(
                this.workflowId,
                this.projectId,
                "Full Testing",
                "full testing",
                List.of(new Node(
                        this.nodeId,
                        this.agentId,
                        com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(
                                new NodePort(this.inputPortId, "Input", "Input", 0),
                                new NodePort(this.secondInputPortId, "Alternate Input", "Alternate Input", 1)
                        ),
                        List.of(new NodePort(taskOutputPortId, outputName, outputDescription, 0)),
                        new NodePosition(1.0, 2.0),
                        com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                )),
                List.of(),
                taskInputPortId,
                taskOutputPortId,
                NOW,
                NOW
        );
    }

    private Workflow workflowWithTwoNodes() {
        return new Workflow(
                this.workflowId,
                this.projectId,
                "Full Testing",
                "full testing",
                List.of(
                        new Node(
                                this.nodeId,
                                this.agentId,
                                com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                                List.of(new NodePort(this.inputPortId, "Input", "Input", 0)),
                                List.of(new NodePort(this.outputPortId, "Done", "Done", 0)),
                                new NodePosition(1.0, 2.0),
                                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                        ),
                        new Node(
                                this.secondNodeId,
                                this.secondAgentId,
                                com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                                List.of(new NodePort(this.secondInputPortId, "Review feedback", "Review feedback", 0)),
                                List.of(new NodePort(this.secondOutputPortId, "Approved", "Approved", 0),
                                        new NodePort(this.terminalOutputPortId, "Task Result", "Task Result", 1)),
                                new NodePosition(3.0, 4.0),
                                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                        )
                ),
                List.of(
                        new WorkflowConnection(this.connectionId, this.outputPortId, this.secondInputPortId),
                        new WorkflowConnection(UUID.fromString("99999999-9999-4999-8999-999999999998"), this.secondOutputPortId, this.inputPortId)
                ),
                this.inputPortId,
                this.terminalOutputPortId,
                NOW,
                NOW
        );
    }

    private Node withScope(final Node node, final NodeScopeMode scopeMode) {
        return new Node(node.id(), node.targetId(), node.inputMode(), node.inputs(), node.outputs(), node.position(), scopeMode);
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

    private AgentDefinition secondAgent() {
        return new AgentDefinition(
                this.secondAgentId,
                this.projectId,
                "Reviewer Agent",
                "reviewer agent",
                "Reviewer instructions.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                new AgentModelSelection("codex", "gpt-5", null),
                NOW,
                NOW
        );
    }
}
