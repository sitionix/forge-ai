package com.sitionix.forgeagent.it;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createWorkflowRun;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.application.runtime.NodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.NodeRunWorker;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentNodeRunRuntimeIT {

    private static final UUID NODE_D_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private NodeRunRepository nodeRunRepository;
    @Autowired
    private NodeRunLifecycle nodeRunLifecycle;

    @Test
    void givenDiamondDag_whenWorkerPolls_thenBranchesExecuteIndependentlyAndWorkflowSucceeds() throws Exception {
        this.seedProjectAgentsAndWorkflow();
        this.configureAllAgentsWithModelA();
        this.updateWorkflow("requestUpdateWorkflowFanInGraph.json");
        final WorkflowRunEntity run = this.createRun(4);
        final Map<UUID, NodeRunEntity> initialNodes = this.nodeRunsBySourceNodeId(run.getId());
        assertThat(initialNodes.values()).extracting(NodeRunEntity::getStatus).containsOnly("PENDING");
        assertThat(run.getStatus()).isEqualTo("QUEUED");

        final DeterministicAgentExecutor agentExecutor = new DeterministicAgentExecutor();
        final TrackingExecutorService executorService = new TrackingExecutorService();
        final NodeRunWorker worker = new NodeRunWorker(this.nodeRunRepository, this.nodeRunLifecycle, agentExecutor, executorService);
        try {
            final UUID nodeRunA = initialNodes.get(NODE_A_ID).getId();
            final UUID nodeRunB = initialNodes.get(NODE_B_ID).getId();
            final UUID nodeRunC = initialNodes.get(NODE_C_ID).getId();
            final UUID nodeRunD = initialNodes.get(NODE_D_ID).getId();

            worker.poll();
            agentExecutor.awaitStarted(nodeRunA);
            assertThat(this.nodeRun(nodeRunA).getStatus()).isEqualTo("RUNNING");
            assertThat(this.workflowRun(run.getId()).getStatus()).isEqualTo("RUNNING");

            agentExecutor.completeSuccess(nodeRunA, "{\"node\":\"A\"}");
            executorService.awaitCompletedCount(1);
            assertThat(this.nodeRun(nodeRunA).getStatus()).isEqualTo("SUCCEEDED");

            worker.poll();
            agentExecutor.awaitStarted(nodeRunB);
            agentExecutor.awaitStarted(nodeRunC);
            assertThat(this.nodeRun(nodeRunB).getStatus()).isEqualTo("RUNNING");
            assertThat(this.nodeRun(nodeRunC).getStatus()).isEqualTo("RUNNING");
            assertThat(this.nodeRun(nodeRunD).getStatus()).isEqualTo("PENDING");

            agentExecutor.completeSuccess(nodeRunB, "{\"node\":\"B\"}");
            executorService.awaitCompletedCount(2);
            assertThat(this.nodeRun(nodeRunB).getStatus()).isEqualTo("SUCCEEDED");
            assertThat(this.nodeRun(nodeRunD).getStatus()).isEqualTo("PENDING");

            agentExecutor.completeSuccess(nodeRunC, "{\"node\":\"C\"}");
            executorService.awaitCompletedCount(3);
            worker.poll();
            agentExecutor.awaitStarted(nodeRunD);
            assertThat(this.nodeRun(nodeRunD).getStatus()).isEqualTo("RUNNING");

            agentExecutor.completeSuccess(nodeRunD, "{\"node\":\"D\"}");
            executorService.awaitCompletedCount(4);

            assertThat(this.nodeRuns(run.getId())).extracting(NodeRunEntity::getStatus).containsOnly("SUCCEEDED");
            assertThat(this.nodeRuns(run.getId())).extracting(NodeRunEntity::getOutput)
                    .allSatisfy(output -> assertThat(output).contains("\"node\""));
            assertThat(this.workflowRun(run.getId())).satisfies(finished -> {
                assertThat(finished.getStatus()).isEqualTo("SUCCEEDED");
                assertThat(finished.getStartedAt()).isNotNull();
                assertThat(finished.getFinishedAt()).isNotNull();
            });
        } finally {
            agentExecutor.completeAll();
            executorService.shutdownNow();
        }
    }

    @Test
    void givenChainDag_whenRootFails_thenDependentsBecomeBlockedAndNeverExecute() throws Exception {
        this.seedProjectAgentsAndWorkflow();
        this.configureAllAgentsWithModelA();
        this.updateWorkflow("requestWorkflowChainABC.json");
        final WorkflowRunEntity run = this.createRun(3);
        final Map<UUID, NodeRunEntity> nodes = this.nodeRunsBySourceNodeId(run.getId());

        final DeterministicAgentExecutor agentExecutor = new DeterministicAgentExecutor();
        final TrackingExecutorService executorService = new TrackingExecutorService();
        final NodeRunWorker worker = new NodeRunWorker(this.nodeRunRepository, this.nodeRunLifecycle, agentExecutor, executorService);
        try {
            final UUID nodeRunA = nodes.get(NODE_A_ID).getId();
            final UUID nodeRunB = nodes.get(NODE_B_ID).getId();
            final UUID nodeRunC = nodes.get(NODE_C_ID).getId();

            worker.poll();
            agentExecutor.awaitStarted(nodeRunA);
            agentExecutor.completeFailure(nodeRunA, "Root failed.");
            executorService.awaitCompletedCount(1);

            worker.poll();
            if ("PENDING".equals(this.nodeRun(nodeRunC).getStatus())) {
                worker.poll();
            }

            assertThat(this.nodeRun(nodeRunA).getStatus()).isEqualTo("FAILED");
            assertThat(this.nodeRun(nodeRunB).getStatus()).isEqualTo("BLOCKED");
            assertThat(this.nodeRun(nodeRunC).getStatus()).isEqualTo("BLOCKED");
            assertThat(this.workflowRun(run.getId()).getStatus()).isEqualTo("FAILED");
            assertThat(agentExecutor.startedNodeRunIds()).containsExactly(nodeRunA);
        } finally {
            agentExecutor.completeAll();
            executorService.shutdownNow();
        }
    }

    @Test
    void givenAgentModelChangesAroundClaim_whenNodeRunStarts_thenExecutionModelIsCurrentAndSnapshotRemainsImmutable() throws Exception {
        this.seedProjectAgentsAndWorkflow();
        this.updateAgent(AGENT_A_ID, "requestUpdateAgentAWithModelA.json");
        this.updateWorkflow("requestWorkflowSingleNodeA.json");
        final WorkflowRunEntity run = this.createRun(1);
        final NodeRunEntity nodeRun = this.nodeRunsBySourceNodeId(run.getId()).get(NODE_A_ID);

        this.updateAgent(AGENT_A_ID, "requestUpdateAgentWithModelB.json");

        final DeterministicAgentExecutor agentExecutor = new DeterministicAgentExecutor();
        final TrackingExecutorService executorService = new TrackingExecutorService();
        final NodeRunWorker worker = new NodeRunWorker(this.nodeRunRepository, this.nodeRunLifecycle, agentExecutor, executorService);
        try {
            worker.poll();
            agentExecutor.awaitStarted(nodeRun.getId());

            assertThat(this.nodeRun(nodeRun.getId())).satisfies(started -> {
                assertThat(started.getStatus()).isEqualTo("RUNNING");
                assertThat(started.getExecutionModelProviderId()).isEqualTo("codex");
                assertThat(started.getExecutionModelId()).isEqualTo("model-b");
                assertThat(started.getExecutionModelEffortId()).isEqualTo("xhigh");
                assertThat(started.getAgentInstructions()).isEqualTo("Do work for Agent A.");
                assertThat(started.getAgentOutputSchema()).contains("\"type\"").contains("\"object\"");
            });

            this.updateAgent(AGENT_A_ID, "requestUpdateAgentAWithModelA.json");
            assertThat(this.nodeRun(nodeRun.getId()).getExecutionModelId()).isEqualTo("model-b");

            agentExecutor.completeSuccess(nodeRun.getId(), "{\"node\":\"A\"}");
            executorService.awaitCompletedCount(1);
            assertThat(this.nodeRun(nodeRun.getId()).getExecutionModelId()).isEqualTo("model-b");
        } finally {
            agentExecutor.completeAll();
            executorService.shutdownNow();
        }
    }

    @Test
    void givenSamePendingNodeRun_whenTwoTryStartCallsRace_thenExactlyOneClaimPersistsRunningState() throws Exception {
        this.seedProjectAgentsAndWorkflow();
        this.updateAgent(AGENT_A_ID, "requestUpdateAgentAWithModelA.json");
        this.updateWorkflow("requestWorkflowSingleNodeA.json");
        final WorkflowRunEntity run = this.createRun(1);
        final UUID nodeRunId = this.nodeRunsBySourceNodeId(run.getId()).get(NODE_A_ID).getId();

        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            final CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> this.tryStartAfterBarrier(barrier, nodeRunId), executor);
            final CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> this.tryStartAfterBarrier(barrier, nodeRunId), executor);

            assertThat(List.of(
                    first.get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                    second.get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)
            )).containsExactlyInAnyOrder(true, false);
            assertThat(this.nodeRun(nodeRunId)).satisfies(started -> {
                assertThat(started.getStatus()).isEqualTo("RUNNING");
                assertThat(started.getStartedAt()).isNotNull();
            });
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean tryStartAfterBarrier(final CyclicBarrier barrier, final UUID nodeRunId) {
        try {
            barrier.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            return this.nodeRunLifecycle.tryStart(nodeRunId).isPresent();
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void configureAllAgentsWithModelA() {
        this.updateAgent(AGENT_A_ID, "requestUpdateAgentAWithModelA.json");
        this.updateAgent(AGENT_B_ID, "requestUpdateAgentBWithModelA.json");
        this.updateAgent(AGENT_C_ID, "requestUpdateAgentCWithModelA.json");
    }

    private void updateAgent(final UUID agentId, final String requestFixture) {
        this.forgeIt.mockMvc()
                .ping(ForgeAgentMockMvcEndpoint.updateAgent())
                .withPathParameters(PathParams.create().add("agentId", agentId))
                .withRequest(requestFixture)
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
    }

    private void updateWorkflow(final String requestFixture) {
        this.forgeIt.mockMvc()
                .ping(ForgeAgentMockMvcEndpoint.updateWorkflow())
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest(requestFixture)
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
    }

    private WorkflowRunEntity createRun(final int expectedNodeRunCount) {
        this.forgeIt.mockMvc()
                .ping(createWorkflowRun())
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestCreateWorkflowRun.json")
                .expectStatus(HttpStatus.CREATED)
                .andExpectPath(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/workflow-runs/")))
                .andExpectPath(jsonPath("$.nodeRuns.length()").value(expectedNodeRunCount))
                .assertAndCreate();
        return this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll().stream()
                .max(Comparator.comparing(WorkflowRunEntity::getCreatedAt).thenComparing(WorkflowRunEntity::getId))
                .orElseThrow();
    }

    private WorkflowRunEntity workflowRun(final UUID workflowRunId) {
        return this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll().stream()
                .filter(run -> workflowRunId.equals(run.getId()))
                .findFirst()
                .orElseThrow();
    }

    private NodeRunEntity nodeRun(final UUID nodeRunId) {
        return this.forgeIt.postgresql().get(NodeRunEntity.class).getAll().stream()
                .filter(nodeRun -> nodeRunId.equals(nodeRun.getId()))
                .findFirst()
                .orElseThrow();
    }

    private List<NodeRunEntity> nodeRuns(final UUID workflowRunId) {
        return this.forgeIt.postgresql().get(NodeRunEntity.class).getAll().stream()
                .filter(nodeRun -> workflowRunId.equals(nodeRun.getWorkflowRunId()))
                .toList();
    }

    private Map<UUID, NodeRunEntity> nodeRunsBySourceNodeId(final UUID workflowRunId) {
        return this.nodeRuns(workflowRunId).stream()
                .collect(Collectors.toMap(NodeRunEntity::getSourceNodeId, nodeRun -> nodeRun));
    }

    private void seedProjectAgentsAndWorkflow() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json"))
                .to(AGENT_DEFINITION.withJson("agent_c.json"))
                .to(WORKFLOW.withJson("workflow_alpha.json"))
                .build();
    }

    private static final class DeterministicAgentExecutor implements AgentExecutor {

        private final BlockingQueue<NodeExecutionClaim> started = new LinkedBlockingQueue<>();
        private final ConcurrentMap<UUID, NodeExecutionClaim> startedByNodeRunId = new ConcurrentHashMap<>();
        private final ConcurrentMap<UUID, CompletableFuture<NodeRunOutput>> results = new ConcurrentHashMap<>();

        @Override
        public NodeRunOutput execute(final NodeExecutionClaim claim) {
            this.startedByNodeRunId.put(claim.nodeRunId(), claim);
            this.results.computeIfAbsent(claim.nodeRunId(), ignored -> new CompletableFuture<>());
            this.started.add(claim);
            try {
                return this.results.get(claim.nodeRunId()).get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for deterministic result.", exception);
            } catch (final Exception exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }

        NodeExecutionClaim awaitStarted(final UUID nodeRunId) throws Exception {
            final NodeExecutionClaim existing = this.startedByNodeRunId.get(nodeRunId);
            if (existing != null) {
                return existing;
            }
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                final NodeExecutionClaim claim = this.started.poll(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
                if (claim == null) {
                    break;
                }
                if (nodeRunId.equals(claim.nodeRunId())) {
                    return claim;
                }
            }
            throw new AssertionError("Node run did not start: " + nodeRunId);
        }

        void completeSuccess(final UUID nodeRunId, final String jsonOutput) {
            this.results.computeIfAbsent(nodeRunId, ignored -> new CompletableFuture<>())
                    .complete(new NodeRunOutput(jsonOutput));
        }

        void completeFailure(final UUID nodeRunId, final String message) {
            this.results.computeIfAbsent(nodeRunId, ignored -> new CompletableFuture<>())
                    .completeExceptionally(new IllegalStateException(message));
        }

        List<UUID> startedNodeRunIds() {
            return this.startedByNodeRunId.keySet().stream().toList();
        }

        void completeAll() {
            this.results.values().forEach(result -> result.complete(new NodeRunOutput("{\"cancelled\":true}")));
        }
    }

    private static final class TrackingExecutorService extends AbstractExecutorService {

        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        private final Object monitor = new Object();
        private int submittedCount;
        private int completedCount;
        private boolean shutdown;

        @Override
        public void shutdown() {
            this.shutdown = true;
            this.delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.shutdown = true;
            return this.delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            return this.delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(final Runnable command) {
            synchronized (this.monitor) {
                this.submittedCount++;
                this.monitor.notifyAll();
            }
            this.delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    synchronized (this.monitor) {
                        this.completedCount++;
                        this.monitor.notifyAll();
                    }
                }
            });
        }

        void awaitCompletedCount(final int expectedCount) throws InterruptedException {
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            synchronized (this.monitor) {
                while (this.completedCount < expectedCount) {
                    final long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMillis <= 0) {
                        throw new AssertionError("Expected completed tasks: " + expectedCount + ", actual: " + this.completedCount);
                    }
                    this.monitor.wait(remainingMillis);
                }
            }
        }
    }
}
