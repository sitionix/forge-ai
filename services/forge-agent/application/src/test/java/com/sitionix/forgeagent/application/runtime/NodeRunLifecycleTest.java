package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
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
    private static final UUID OTHER_WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000101");
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID WORKFLOW_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID AGENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_A = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_B = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-11T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AgentOutputSchema SNAPSHOT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\",\"properties\":{\"snapshot\":{\"type\":\"string\"}}}");

    @Mock
    private NodeRunRepository nodeRunRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;
    @Mock
    private AgentDefinitionRepository agentDefinitionRepository;

    private final Map<UUID, NodeRun> nodeRuns = new LinkedHashMap<>();
    private final Map<UUID, AgentDefinition> agents = new LinkedHashMap<>();
    private WorkflowRun workflowRun;
    private NodeRunLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        this.lifecycle = new NodeRunLifecycle(this.nodeRunRepository, this.workflowRunRepository, this.agentDefinitionRepository, CLOCK);
        this.workflowRun = this.workflowRun(WorkflowRunStatus.QUEUED, null, null);
        this.agents.put(AGENT_ID, this.agent(new AgentModelSelection("codex", "model-a", "medium")));
        this.stubRepositories();
    }

    @Test
    void rootPendingNodeClaimsAndStartsWorkflowRun() {
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.PENDING));

        final Optional<NodeExecutionClaim> claim = this.lifecycle.tryStart(NODE_RUN_A);

        assertThat(claim).isPresent();
        assertThat(this.nodeRuns.get(NODE_RUN_A).status()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(this.nodeRuns.get(NODE_RUN_A).startedAt()).isEqualTo(NOW);
        assertThat(this.nodeRuns.get(NODE_RUN_A).executionModel()).isEqualTo(new NodeRunExecutionModel("codex", "model-a", "medium"));
        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(this.workflowRun.startedAt()).isEqualTo(NOW);
        assertThat(claim.orElseThrow()).satisfies(execution -> {
            assertThat(execution.workflowRunId()).isEqualTo(WORKFLOW_RUN_ID);
            assertThat(execution.nodeRunId()).isEqualTo(NODE_RUN_A);
            assertThat(execution.workflowInput()).isEqualTo("Review auth changes.");
            assertThat(execution.agentInstructions()).isEqualTo("Snapshot instructions.");
            assertThat(execution.outputSchema()).isEqualTo(SNAPSHOT_SCHEMA);
            assertThat(execution.dependencies()).isEmpty();
        });
    }

    @Test
    void pendingNodeWithSucceededDependenciesClaimsWithDependencyOutputsInPersistedOrder() {
        final NodeRunOutput outputA = new NodeRunOutput("{\"a\":true}");
        this.nodeRuns.put(NODE_RUN_A, this.withOutput(this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.SUCCEEDED), outputA));
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(NODE_RUN_A), NodeRunStatus.PENDING));

        final Optional<NodeExecutionClaim> claim = this.lifecycle.tryStart(NODE_RUN_B);

        assertThat(claim).isPresent();
        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(claim.orElseThrow().dependencies()).containsExactly(new NodeDependencyOutput(NODE_RUN_A, "Snapshot Agent", outputA));
    }

    @Test
    void pendingOrRunningDependencyMakesClaimNoOp() {
        this.assertDependencyNoOp(NodeRunStatus.PENDING);
        this.assertDependencyNoOp(NodeRunStatus.RUNNING);
    }

    @Test
    void failedBlockedOrCancelledDependencyBlocksPendingNode() {
        this.assertDependencyBlocks(NodeRunStatus.FAILED);
        this.assertDependencyBlocks(NodeRunStatus.BLOCKED);
        this.assertDependencyBlocks(NodeRunStatus.CANCELLED);
    }

    @Test
    void runningOrSucceededNodeDoesNotClaim() {
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.RUNNING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_A)).isEmpty();

        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.SUCCEEDED));

        assertThat(this.lifecycle.tryStart(NODE_RUN_A)).isEmpty();

        verify(this.agentDefinitionRepository, never()).findById(any());
    }

    @Test
    void modelSelectionIsResolvedFromCurrentAgentAtClaimWhileSnapshotFieldsRemainFromNodeRun() {
        this.agents.put(AGENT_ID, this.agent(new AgentModelSelection("codex", "model-b", "xhigh")));
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.PENDING));

        final NodeExecutionClaim claim = this.lifecycle.tryStart(NODE_RUN_A).orElseThrow();

        assertThat(claim.executionModel()).isEqualTo(new NodeRunExecutionModel("codex", "model-b", "xhigh"));
        assertThat(claim.agentInstructions()).isEqualTo("Snapshot instructions.");
        assertThat(claim.outputSchema()).isEqualTo(SNAPSHOT_SCHEMA);
    }

    @Test
    void modelSelectionWithoutEffortClaimsSuccessfully() {
        this.agents.put(AGENT_ID, this.agent(new AgentModelSelection("codex", "model-without-effort", null)));
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.PENDING));

        final NodeExecutionClaim claim = this.lifecycle.tryStart(NODE_RUN_A).orElseThrow();

        assertThat(this.nodeRuns.get(NODE_RUN_A).status()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(claim.executionModel()).isEqualTo(new NodeRunExecutionModel("codex", "model-without-effort", null));
        assertThat(this.nodeRuns.get(NODE_RUN_A).executionModel()).isEqualTo(new NodeRunExecutionModel("codex", "model-without-effort", null));
    }

    @Test
    void agentModelChangedAfterClaimDoesNotMutatePersistedExecutionModel() {
        this.agents.put(AGENT_ID, this.agent(new AgentModelSelection("codex", "model-b", "xhigh")));
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.PENDING));

        this.lifecycle.tryStart(NODE_RUN_A);
        this.agents.put(AGENT_ID, this.agent(new AgentModelSelection("codex", "model-c", "low")));

        assertThat(this.nodeRuns.get(NODE_RUN_A).executionModel()).isEqualTo(new NodeRunExecutionModel("codex", "model-b", "xhigh"));
    }

    @Test
    void missingSourceAgentFailsSafely() {
        this.agents.clear();
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.PENDING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_A)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_A).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_A).failure().code()).isEqualTo(NodeRunLifecycle.SOURCE_AGENT_NOT_FOUND);
    }

    @Test
    void nullAgentModelFailsSafely() {
        this.agents.put(AGENT_ID, this.agent(null));
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.PENDING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_A)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_A).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_A).failure().code()).isEqualTo(NodeRunLifecycle.AGENT_MODEL_NOT_CONFIGURED);
    }

    @Test
    void missingDependencyFailsClosed() {
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(NODE_RUN_A), NodeRunStatus.PENDING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_B)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_B).failure())
                .isEqualTo(new NodeRunFailure(NodeRunLifecycle.INVALID_NODE_RUN_DEPENDENCY, "Node run dependency is invalid."));
        assertThat(this.nodeRuns.get(NODE_RUN_B).finishedAt()).isEqualTo(NOW);
    }

    @Test
    void crossWorkflowRunDependencyFailsClosed() {
        this.nodeRuns.put(NODE_RUN_A, this.withWorkflowRunId(this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.SUCCEEDED), OTHER_WORKFLOW_RUN_ID));
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(NODE_RUN_A), NodeRunStatus.PENDING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_B)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_B).failure().code()).isEqualTo(NodeRunLifecycle.INVALID_NODE_RUN_DEPENDENCY);
    }

    @Test
    void terminalWorkflowRunDoesNotClaimPendingNode() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.CANCELLED, NOW, NOW);
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.PENDING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_A)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_A).status()).isEqualTo(NodeRunStatus.PENDING);
        verify(this.agentDefinitionRepository, never()).findById(any());
    }

    @Test
    void successAndFailureCompleteRunningNodeRuns() {
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.RUNNING));
        this.lifecycle.succeed(NODE_RUN_A, new NodeRunOutput("{\"ok\":true}"));

        assertThat(this.nodeRuns.get(NODE_RUN_A).status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(this.nodeRuns.get(NODE_RUN_A).finishedAt()).isEqualTo(NOW);
        assertThat(this.nodeRuns.get(NODE_RUN_A).output()).isEqualTo(new NodeRunOutput("{\"ok\":true}"));

        this.nodeRuns.clear();
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(), NodeRunStatus.RUNNING));
        this.lifecycle.fail(NODE_RUN_B, new NodeRunFailure("EXECUTION_FAILED", "Executor failed."));

        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_B).failure()).isEqualTo(new NodeRunFailure("EXECUTION_FAILED", "Executor failed."));
    }

    @Test
    void duplicateSameTerminalCompletionIsIdempotent() {
        final NodeRunOutput output = new NodeRunOutput("{\"ok\":true}");
        this.nodeRuns.put(NODE_RUN_A, this.withOutput(this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.SUCCEEDED), output));
        this.lifecycle.succeed(NODE_RUN_A, output);

        final NodeRunFailure failure = new NodeRunFailure("EXECUTION_FAILED", "Executor failed.");
        this.nodeRuns.put(NODE_RUN_B, this.withFailure(this.nodeRun(NODE_RUN_B, List.of(), NodeRunStatus.FAILED), failure));
        this.lifecycle.fail(NODE_RUN_B, failure);

        assertThat(this.nodeRuns.get(NODE_RUN_A).status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.FAILED);
    }

    @Test
    void conflictingTerminalCompletionIsRejected() {
        this.nodeRuns.put(NODE_RUN_A, this.withFailure(
                this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.FAILED),
                new NodeRunFailure("EXECUTION_FAILED", "Executor failed.")
        ));
        assertThatThrownBy(() -> this.lifecycle.succeed(NODE_RUN_A, new NodeRunOutput("{\"ok\":true}")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(NodeRunLifecycle.LIFECYCLE_CONFLICT);

        this.nodeRuns.put(NODE_RUN_B, this.withOutput(this.nodeRun(NODE_RUN_B, List.of(), NodeRunStatus.SUCCEEDED), new NodeRunOutput("{\"ok\":true}")));
        assertThatThrownBy(() -> this.lifecycle.fail(NODE_RUN_B, new NodeRunFailure("EXECUTION_FAILED", "Executor failed.")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(NodeRunLifecycle.LIFECYCLE_CONFLICT);
    }

    @Test
    void reconciliationSucceedsWorkflowWhenAllNodesSucceeded() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_A, this.withOutput(this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.SUCCEEDED), new NodeRunOutput("{\"a\":true}")));
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(NODE_RUN_A), NodeRunStatus.RUNNING));

        this.lifecycle.succeed(NODE_RUN_B, new NodeRunOutput("{\"b\":true}"));

        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(this.workflowRun.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void reconciliationFailsWorkflowWhenTerminalGraphContainsFailedOrBlockedNode() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_A, this.withFailure(
                this.nodeRun(NODE_RUN_A, List.of(), NodeRunStatus.FAILED),
                new NodeRunFailure("EXECUTION_FAILED", "Executor failed.")
        ));
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(NODE_RUN_A), NodeRunStatus.PENDING));

        this.lifecycle.tryStart(NODE_RUN_B);

        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.BLOCKED);
        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(this.workflowRun.finishedAt()).isEqualTo(NOW);
    }

    private void assertDependencyNoOp(final NodeRunStatus dependencyStatus) {
        this.nodeRuns.clear();
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), dependencyStatus));
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(NODE_RUN_A), NodeRunStatus.PENDING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_B)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.PENDING);
    }

    private void assertDependencyBlocks(final NodeRunStatus dependencyStatus) {
        this.nodeRuns.clear();
        this.workflowRun = this.workflowRun(WorkflowRunStatus.QUEUED, null, null);
        this.nodeRuns.put(NODE_RUN_A, this.nodeRun(NODE_RUN_A, List.of(), dependencyStatus));
        this.nodeRuns.put(NODE_RUN_B, this.nodeRun(NODE_RUN_B, List.of(NODE_RUN_A), NodeRunStatus.PENDING));

        assertThat(this.lifecycle.tryStart(NODE_RUN_B)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_B).status()).isEqualTo(NodeRunStatus.BLOCKED);
        assertThat(this.nodeRuns.get(NODE_RUN_B).finishedAt()).isEqualTo(NOW);
    }

    private void stubRepositories() {
        lenient().when(this.nodeRunRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(this.nodeRuns.get(invocation.getArgument(0))));
        lenient().when(this.nodeRunRepository.findWorkflowRunIdById(any())).thenAnswer(invocation -> Optional.ofNullable(this.nodeRuns.get(invocation.getArgument(0)))
                .map(NodeRun::workflowRunId));
        lenient().when(this.nodeRunRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(this.nodeRuns.get(invocation.getArgument(0))));
        lenient().when(this.nodeRunRepository.findByIds(any())).thenAnswer(invocation -> {
            final Collection<UUID> ids = invocation.getArgument(0);
            return ids.stream().map(this.nodeRuns::get).filter(java.util.Objects::nonNull).toList();
        });
        lenient().when(this.nodeRunRepository.findByWorkflowRunId(any())).thenAnswer(invocation -> this.nodeRuns.values().stream()
                .filter(nodeRun -> invocation.getArgument(0).equals(nodeRun.workflowRunId()))
                .toList());
        lenient().when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> {
            final NodeRun nodeRun = invocation.getArgument(0);
            this.nodeRuns.put(nodeRun.id(), nodeRun);
            return nodeRun;
        });
        lenient().when(this.workflowRunRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(this.workflowRun));
        lenient().when(this.workflowRunRepository.saveLifecycle(any())).thenAnswer(invocation -> {
            this.workflowRun = invocation.getArgument(0);
            return this.workflowRun;
        });
        lenient().when(this.agentDefinitionRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(this.agents.get(invocation.getArgument(0))));
    }

    private WorkflowRun workflowRun(final WorkflowRunStatus status, final Instant startedAt, final Instant finishedAt) {
        return new WorkflowRun(
                WORKFLOW_RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                "Full Testing",
                "Review auth changes.",
                status,
                List.of(),
                Instant.EPOCH,
                startedAt,
                finishedAt
        );
    }

    private NodeRun nodeRun(final UUID id, final List<UUID> dependsOnNodeRunIds, final NodeRunStatus status) {
        return new NodeRun(
                id,
                WORKFLOW_RUN_ID,
                UUID.randomUUID(),
                AGENT_ID,
                "Snapshot Agent",
                "Snapshot instructions.",
                SNAPSHOT_SCHEMA,
                dependsOnNodeRunIds,
                new NodePosition(1.0, 2.0),
                status,
                null,
                null,
                null,
                Instant.EPOCH,
                null,
                null
        );
    }

    private NodeRun withOutput(final NodeRun nodeRun, final NodeRunOutput output) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.dependsOnNodeRunIds(),
                nodeRun.position(),
                nodeRun.status(),
                output,
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt()
        );
    }

    private NodeRun withFailure(final NodeRun nodeRun, final NodeRunFailure failure) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.dependsOnNodeRunIds(),
                nodeRun.position(),
                nodeRun.status(),
                nodeRun.output(),
                failure,
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt()
        );
    }

    private NodeRun withWorkflowRunId(final NodeRun nodeRun, final UUID workflowRunId) {
        return new NodeRun(
                nodeRun.id(),
                workflowRunId,
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.dependsOnNodeRunIds(),
                nodeRun.position(),
                nodeRun.status(),
                nodeRun.output(),
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt()
        );
    }

    private AgentDefinition agent(final AgentModelSelection model) {
        return new AgentDefinition(
                AGENT_ID,
                PROJECT_ID,
                "Current Agent",
                "current agent",
                "Current instructions.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\",\"properties\":{\"current\":{\"type\":\"string\"}}}"),
                model,
                Instant.EPOCH,
                Instant.EPOCH
        );
    }
}
