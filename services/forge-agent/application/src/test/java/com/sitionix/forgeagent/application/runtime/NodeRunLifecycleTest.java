package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeRunLifecycleTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID WORKFLOW_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID AGENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID FRAME_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-11T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AgentOutputSchema SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}");
    private static final NodeRunExecutionModel MODEL = new NodeRunExecutionModel("codex", "gpt-5", null);

    @Mock
    private NodeRunRepository nodeRunRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;
    @Mock
    private ConnectionResolutionRepository resolutionRepository;
    @Mock
    private NodeInputContentPolicyRegistry inputContentPolicyRegistry;
    @Mock
    private WorkflowExecutionCoordinator coordinator;
    @Mock
    private WorkflowCompletionPolicy completionPolicy;
    @Mock
    private NodeRunCompletionProcessor completionProcessor;

    private final Map<UUID, NodeRun> nodeRuns = new LinkedHashMap<>();
    private WorkflowRun workflowRun;
    private NodeRunLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        this.lifecycle = new NodeRunLifecycle(
                this.nodeRunRepository,
                this.workflowRunRepository,
                this.resolutionRepository,
                this.inputContentPolicyRegistry,
                this.coordinator,
                this.completionPolicy,
                CLOCK,
                new NodeRunCompletionPersistence(
                        this.nodeRunRepository,
                        this.workflowRunRepository,
                        this.completionPolicy,
                        this.coordinator,
                        CLOCK
                ),
                this.completionProcessor
        );
        this.workflowRun = this.workflowRun(WorkflowRunStatus.QUEUED, null, null);
        this.stubRepositories();
    }

    @Test
    void pendingNodeClaimsUsingSnapshottedExecutionModelAndInputContentPolicy() {
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.PENDING, MODEL));
        when(this.resolutionRepository.findConsumedByNodeRunId(NODE_RUN_ID)).thenReturn(List.of());
        when(this.inputContentPolicyRegistry.assemble(any())).thenReturn(new NodeExecutionInputContent(
                new NodeInputEnvelope("Review auth changes.", null, List.of())
        ));

        final Optional<NodeExecutionClaim> claim = this.lifecycle.tryStart(NODE_RUN_ID);

        assertThat(claim).isPresent();
        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(claim.orElseThrow()).satisfies(execution -> {
            assertThat(execution.nodeRunId()).isEqualTo(NODE_RUN_ID);
            assertThat(execution.executionModel()).isEqualTo(MODEL);
            assertThat(execution.workflowInput()).isEqualTo("Review auth changes.");
            assertThat(execution.inputEnvelope().originalTask()).isEqualTo("Review auth changes.");
            assertThat(execution.inputEnvelope().contributions()).isEmpty();
        });
    }

    @Test
    void invalidSnapshottedModelFailsNodeRunWithoutClaiming() {
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.PENDING, null));

        assertThat(this.lifecycle.tryStart(NODE_RUN_ID)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).failure().code()).isEqualTo(NodeRunLifecycle.AGENT_MODEL_NOT_CONFIGURED);
        verifyNoInteractions(this.inputContentPolicyRegistry);
    }

    @Test
    void successPersistsOutputAndDelegatesGraphContinuation() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.RUNNING, MODEL));
        final NodeRunOutput output = new NodeRunOutput("{\"ok\":true}");

        this.lifecycle.succeed(NODE_RUN_ID, output);

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).output()).isEqualTo(output);
        verify(this.completionProcessor).process(NODE_RUN_ID);
    }

    @Test
    void nonRunningSuccessIsRejected() {
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.PENDING, MODEL));

        assertThatThrownBy(() -> this.lifecycle.succeed(NODE_RUN_ID, new NodeRunOutput("{\"ok\":true}")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(NodeRunLifecycle.LIFECYCLE_CONFLICT);
    }

    @Test
    void failureCompletesRunningNodeRun() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.RUNNING, MODEL));

        this.lifecycle.fail(NODE_RUN_ID, new NodeRunFailure("EXECUTION_FAILED", "Executor failed."));

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).failure()).isEqualTo(new NodeRunFailure("EXECUTION_FAILED", "Executor failed."));
        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.FAILED);
    }

    private void stubRepositories() {
        lenient().when(this.nodeRunRepository.findWorkflowRunIdById(any())).thenAnswer(invocation -> Optional.ofNullable(this.nodeRuns.get(invocation.getArgument(0)))
                .map(NodeRun::workflowRunId));
        lenient().when(this.nodeRunRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(this.nodeRuns.get(invocation.getArgument(0))));
        lenient().when(this.nodeRunRepository.findByWorkflowRunId(any())).thenAnswer(invocation -> this.nodeRuns.values().stream()
                .filter(nodeRun -> invocation.getArgument(0).equals(nodeRun.workflowRunId()))
                .toList());
        lenient().when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> {
            final NodeRun nodeRun = invocation.getArgument(0);
            this.nodeRuns.put(nodeRun.id(), nodeRun);
            return nodeRun;
        });
        lenient().when(this.nodeRunRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final NodeRun nodeRun = invocation.getArgument(0);
            this.nodeRuns.put(nodeRun.id(), nodeRun);
            return nodeRun;
        });
        lenient().when(this.workflowRunRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(this.workflowRun));
        lenient().when(this.workflowRunRepository.saveLifecycle(any())).thenAnswer(invocation -> {
            this.workflowRun = invocation.getArgument(0);
            return this.workflowRun;
        });
        lenient().when(this.completionPolicy.evaluate(any())).thenReturn(new FailedWorkflowDecision());
        lenient().when(this.coordinator.completionDecisionHandler(any())).thenAnswer(invocation -> new WorkflowCompletionDecisionHandler() {
            @Override
            public void handle(final RunningWorkflowDecision decision) {
            }

            @Override
            public void handle(final SuccessfulWorkflowDecision decision) {
                NodeRunLifecycleTest.this.workflowRun = NodeRunLifecycleTest.this.workflowRun(WorkflowRunStatus.SUCCEEDED, NOW, NOW);
            }

            @Override
            public void handle(final FailedWorkflowDecision decision) {
                NodeRunLifecycleTest.this.workflowRun = NodeRunLifecycleTest.this.workflowRun(WorkflowRunStatus.FAILED, NOW, NOW);
            }
        });
    }

    private WorkflowRun workflowRun(final WorkflowRunStatus status, final Instant startedAt, final Instant finishedAt) {
        return new WorkflowRun(
                WORKFLOW_RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                status,
                List.of(),
                Instant.EPOCH,
                startedAt,
                finishedAt
        );
    }

    private NodeRun nodeRun(final NodeRunStatus status, final NodeRunExecutionModel executionModel) {
        return new NodeRun(
                NODE_RUN_ID,
                WORKFLOW_RUN_ID,
                UUID.randomUUID(),
                AGENT_ID,
                "Snapshot Agent",
                "Snapshot instructions.",
                SCHEMA,
                NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(1.0, 2.0),
                FRAME_ID,
                null,
                null,
                null,
                status,
                null,
                null,
                executionModel,
                Instant.EPOCH,
                null,
                null
        );
    }

}
