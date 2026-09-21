package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@IntegrationTest
class ForgeAgentManualRuntimeIT {
    @Autowired private ForgeAgentTestManager forgeIt;
    @Autowired private WorkflowUseCases workflows;
    @Autowired private WorkflowRunUseCases runs;
    @Autowired private CancelWorkflowRunUseCase cancelRun;
    @Autowired private NodeRunWorker worker;
    @Autowired private ManualNodeRunLifecycle manualLifecycle;
    @Autowired private NodeRunLifecycle agentLifecycle;
    @Autowired private AgentSessionLeaseService leases;
    @Autowired private AgentExecutionRecoveryService recovery;
    @Autowired private NodeRunRepository nodeRuns;
    @Autowired private WorkflowExecutionCoordinator coordinator;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private AgentExecutor executor;
    @MockBean private ExecutionWorkspaceResolver workspaceResolver;

    @Test
    void workerWaitsWithoutAgentExecutionAndCompletionKeepsWorkflowActive() {
        WorkflowRun run = createRun(false);
        worker.poll();
        coordinator.reconcile(run.id());

        assertWaiting(run.id());
        assertThat(nodeRuns.findPendingIds()).doesNotContain(run.nodeRuns().getFirst().id());
        verifyNoInteractions(executor, workspaceResolver);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id=?", Long.class, run.id())).isZero();
    }

    @Test
    void persistedWaitingSurvivesReloadAndWorkerRestart() {
        WorkflowRun run = createRun(false);
        UUID invocation = run.nodeRuns().getFirst().id();
        // A pending invocation persisted before a worker starts is picked up from the repository.
        assertThat(nodeRuns.findById(invocation).orElseThrow().status()).isEqualTo(NodeRunStatus.PENDING);
        pollFreshWorker();
        NodeRun waiting = nodeRuns.findById(invocation).orElseThrow();
        assertThat(waiting.status().name()).isEqualTo("WAITING_FOR_MANUAL");
        manualLifecycle.waitForSelection(invocation);
        pollFreshWorker();
        coordinator.reconcile(run.id());
        assertThat(nodeRuns.findById(invocation).orElseThrow()).isEqualTo(waiting);
        assertWaiting(run.id());
        verifyNoInteractions(executor, workspaceResolver);
    }

    @Test
    void cancellationCancelsWaitingManualWithoutProviderCancellation() {
        WorkflowRun run = createRun(false);
        worker.poll();
        assertWaiting(run.id());

        cancelRun.execute(run.id());
        worker.poll();

        WorkflowRun cancelled = runs.getWorkflowRun(run.id());
        assertThat(cancelled.status()).isEqualTo(WorkflowRunStatus.CANCELLED);
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled.nodeRuns()).singleElement().satisfies(node -> {
            assertThat(node.nodeType()).isEqualTo(NodeType.MANUAL);
            assertThat(node.status()).isEqualTo(NodeRunStatus.CANCELLED);
            assertThat(node.finishedAt()).isNotNull();
        });
        verifyNoInteractions(executor, workspaceResolver);
    }

    @Test
    void existingAgentExecutionRoutesToManualAndStopsThere() {
        WorkflowRun run = createRun(true);
        manualLifecycle.waitForSelection(run.nodeRuns().getFirst().id());
        assertThat(nodeRuns.findById(run.nodeRuns().getFirst().id()).orElseThrow().status()).isEqualTo(NodeRunStatus.PENDING);
        when(workspaceResolver.resolve(any(), any())).thenReturn(new ExecutionWorkspace(Path.of("/tmp"), List.of()));
        when(executor.execute(any())).thenReturn(new AgentExecutionResult(new NodeRunOutput("{\"done\":true}"), null));

        worker.poll();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(nodeRuns.findByWorkflowRunId(run.id())).hasSize(2));
        worker.poll();
        coordinator.reconcile(run.id());

        assertWaiting(run.id());
        assertThat(nodeRuns.findByWorkflowRunId(run.id())).filteredOn(node -> node.nodeType() == NodeType.AGENT)
                .singleElement().satisfies(node -> assertThat(node.status()).isEqualTo(NodeRunStatus.SUCCEEDED));
        verify(executor, times(1)).execute(any());
        verify(workspaceResolver, times(1)).resolve(any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id=?", Long.class, run.id())).isEqualTo(1);
    }

    @Test
    void concurrentCancellationAndManualStartCannotReviveCancelledRun() throws Exception {
        WorkflowRun run = createRun(false);
        UUID id = run.nodeRuns().getFirst().id();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var waiting = threads.submit(() -> {
                start.await();
                manualLifecycle.waitForSelection(id);
                return null;
            });
            var cancellation = threads.submit(() -> {
                start.await();
                cancelRun.execute(run.id());
                return null;
            });
            start.countDown();
            waiting.get(10, java.util.concurrent.TimeUnit.SECONDS);
            cancellation.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        manualLifecycle.waitForSelection(id);
        assertThat(runs.getWorkflowRun(run.id()).status()).isEqualTo(WorkflowRunStatus.CANCELLED);
        assertThat(nodeRuns.findById(id).orElseThrow().status()).isEqualTo(NodeRunStatus.CANCELLED);
        verifyNoInteractions(executor, workspaceResolver);
    }

    private void pollFreshWorker() {
        try (var threads = java.util.concurrent.Executors.newSingleThreadExecutor();
             var heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            new NodeRunWorker(nodeRuns, agentLifecycle, executor, threads, heartbeat, leases, recovery, manualLifecycle).poll();
        }
    }

    private void assertWaiting(UUID runId) {
        WorkflowRun persisted = runs.getWorkflowRun(runId);
        assertThat(persisted.status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(persisted.startedAt()).isNotNull();
        assertThat(persisted.finishedAt()).isNull();
        assertThat(persisted.nodeRuns()).filteredOn(node -> node.nodeType() == NodeType.MANUAL)
                .singleElement().satisfies(node -> {
                    assertThat(node.status().name()).isEqualTo("WAITING_FOR_MANUAL");
                    assertThat(node.finishedAt()).isNull();
                    assertThat(node.executionModel()).isNull();
                    assertThat(node.contextTrackingVersion()).isNull();
                    assertThat(node.contextIterationId()).isNull();
                    assertThat(node.failure()).isNull();
                });
    }

    private WorkflowRun createRun(boolean agentFirst) {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        Workflow workflow = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Manual runtime"));
        Node manual = node(null, NodeType.MANUAL);
        List<Node> nodes = List.of(manual);
        List<WorkflowConnection> edges = List.of();
        UUID input = manual.inputs().getFirst().id();
        if (agentFirst) {
            forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
            UUID agentId = jdbc.queryForObject("SELECT id FROM agent_definitions WHERE project_id=?", UUID.class, PROJECT_ALPHA_ID);
            Node agent = node(agentId, NodeType.AGENT);
            nodes = List.of(agent, manual);
            edges = List.of(new WorkflowConnection(UUID.randomUUID(), agent.outputs().getFirst().id(), input));
            input = agent.inputs().getFirst().id();
        }
        workflows.updateWorkflow(workflow.id(), new SaveWorkflowCommand(workflow.name(), nodes, edges, input, manual.outputs().getFirst().id()));
        return runs.createWorkflowRun(workflow.id(), new CreateWorkflowRunCommand("Input"));
    }

    private Node node(UUID target, NodeType type) {
        return new Node(UUID.randomUUID(), target, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
                List.of(new NodePort(UUID.randomUUID(), "Continue", "Continue", 0)),
                new NodePosition(0, 0), NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null, type);
    }
}
