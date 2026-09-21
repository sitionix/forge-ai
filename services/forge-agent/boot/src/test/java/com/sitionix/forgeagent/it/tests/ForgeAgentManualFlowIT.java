package com.sitionix.forgeagent.it.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL, HTTP commands, lifecycle, sessions and routing; only provider execution/workspace are deterministic. */
@IntegrationTest
class ForgeAgentManualFlowIT {
    @Autowired private ForgeAgentTestManager forgeIt;
    @Autowired private WorkflowUseCases workflows;
    @Autowired private WorkflowRunUseCases runs;
    @Autowired private NodeRunWorker worker;
    @Autowired private NodeRunRepository nodeRuns;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private AgentExecutor executor;
    @MockBean private ExecutionWorkspaceResolver workspace;

    @Test
    void implementerManualContinueReviewerCompletesRealPersistedGraph() throws Exception {
        Fixture f = fixture();
        NodeRun manual = reachWaiting(f, 1);
        assertThat(bySource(f, f.implementer())).singleElement().satisfies(n ->
                assertThat(n.status()).isEqualTo(NodeRunStatus.SUCCEEDED));
        assertThat(bySource(f, f.reviewer())).isEmpty();
        assertNoManualExecution(f, 1);

        assertThat(select(f, manual.id(), f.proceed())).isEqualTo(200);
        assertManualDecision(f, manual.id(), f.proceed(), "Continue");
        assertThat(bySource(f, f.reviewer())).singleElement().satisfies(n ->
                assertThat(n.status()).isEqualTo(NodeRunStatus.PENDING));
        finish(f, 3, 2);
        assertThat(select(f, manual.id(), f.proceed())).isEqualTo(200);
        assertThat(nodeRuns.findByWorkflowRunId(f.runId())).hasSize(3);
        mvc.perform(get("/api/v1/workflow-runs/{id}", f.runId())).andExpect(status().isOk());
    }

    @Test
    void retryCreatesNewImplementerAndNewWaitingManualThenContinueCompletes() throws Exception {
        Fixture f = fixture();
        NodeRun first = reachWaiting(f, 1);
        assertThat(select(f, first.id(), f.retry())).isEqualTo(200);
        assertManualDecision(f, first.id(), f.retry(), "Retry");
        NodeRun second = reachWaiting(f, 2);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.executionFrameId()).isNotEqualTo(first.executionFrameId());
        assertThat(second.selectedOutputPortId()).isNull();
        assertThat(second.finishedAt()).isNull();
        assertThat(bySource(f, f.implementer())).hasSize(2);
        assertThat(bySource(f, f.reviewer())).isEmpty();
        assertNoManualExecution(f, 2);
        // Replaying a historical invocation must not select the new waiting invocation or loop again.
        assertThat(select(f, first.id(), f.retry())).isEqualTo(200);
        assertThat(select(f, first.id(), f.proceed())).isEqualTo(409);
        assertThat(nodeRuns.findByWorkflowRunId(f.runId())).hasSize(4);
        assertThat(nodeRuns.findById(second.id()).orElseThrow().status()).isEqualTo(NodeRunStatus.WAITING_FOR_MANUAL);
        assertThat(select(f, second.id(), f.proceed())).isEqualTo(200);
        finish(f, 5, 3);

        var claims = ArgumentCaptor.forClass(NodeExecutionClaim.class);
        verify(executor, times(3)).execute(claims.capture());
        NodeExecutionClaim reentry = claims.getAllValues().stream().filter(c -> c.sourceAgentId().equals(AGENT_A_ID)).toList().get(1);
        assertThat(reentry.inputEnvelope().contributions()).singleElement().satisfies(input -> {
            assertThat(input.sourceNodeRunId()).isEqualTo(first.id());
            assertThat(input.payload().jsonValue()).contains(f.retry().toString());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousSelectionsActivateExactlyOneAgentBranch(boolean sameSelection) throws Exception {
        Fixture f = fixture();
        NodeRun manual = reachWaiting(f, 1);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { start.await(); return select(f, manual.id(), f.proceed()); });
            var second = threads.submit(() -> { start.await(); return select(f, manual.id(), sameSelection ? f.proceed() : f.retry()); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, sameSelection ? 200 : 409);
        }
        NodeRun decision = nodeRuns.findById(manual.id()).orElseThrow();
        boolean retry = decision.selectedOutputPortId().equals(f.retry());
        assertManualDecision(f, manual.id(), decision.selectedOutputPortId(), retry ? "Retry" : "Continue");
        assertThat(nodeRuns.findByWorkflowRunId(f.runId())).hasSize(3);
        assertThat(nodeRuns.findByWorkflowRunId(f.runId())).filteredOn(n -> n.status() == NodeRunStatus.PENDING)
                .singleElement().satisfies(n -> assertThat(n.sourceNodeId()).isEqualTo(retry ? f.implementer().id() : f.reviewer().id()));
        if (retry) {
            NodeRun next = reachWaiting(f, 2);
            assertThat(select(f, next.id(), f.proceed())).isEqualTo(200);
        }
        finish(f, retry ? 5 : 3, retry ? 3 : 2);
    }

    @Test
    void stopWaitingMixedGraphCancelsManualAndWorkflowWithoutProviderCancellation() throws Exception {
        Fixture f = fixture();
        NodeRun manual = reachWaiting(f, 1);
        mvc.perform(post("/api/v1/workflow-runs/{id}/cancel", f.runId())).andExpect(status().isNoContent());
        worker.poll();
        assertThat(runs.getWorkflowRun(f.runId()).status()).isEqualTo(WorkflowRunStatus.CANCELLED);
        NodeRun cancelled = nodeRuns.findById(manual.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(NodeRunStatus.CANCELLED);
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled.selectedOutputPortId()).isNull();
        assertThat(select(f, manual.id(), f.proceed())).isEqualTo(409);
        assertThat(nodeRuns.findByWorkflowRunId(f.runId())).hasSize(2);
        assertThat(bySource(f, f.reviewer())).isEmpty();
        verify(executor, never()).secureCancellation(any());
        verify(executor, never()).cancel(any());
        assertNoManualExecution(f, 1);
    }

    private Fixture fixture() {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json"))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json")).build();
        when(workspace.resolve(any(), any(), any())).thenReturn(new ExecutionWorkspace(Path.of("/tmp"), List.of()));
        when(executor.execute(any())).thenAnswer(invocation -> {
            NodeExecutionClaim claim = invocation.getArgument(0);
            assertThat(claim.sourceAgentId()).isIn(AGENT_A_ID, AGENT_B_ID);
            return new AgentExecutionResult(new NodeRunOutput(claim.sourceAgentId().equals(AGENT_A_ID)
                    ? "{\"implementation\":\"done\"}" : "{\"review\":\"passed\"}"), null);
        });
        Workflow workflow = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Implementer - Manual - Reviewer"));
        Node implementer = node(AGENT_A_ID, NodeType.AGENT, "Done");
        Node manual = node(null, NodeType.MANUAL, "Retry", "Continue");
        Node reviewer = node(AGENT_B_ID, NodeType.AGENT, "Done");
        UUID retry = manual.outputs().getFirst().id(), proceed = manual.outputs().getLast().id();
        workflows.updateWorkflow(workflow.id(), new SaveWorkflowCommand(workflow.name(), List.of(implementer, manual, reviewer),
                List.of(edge(implementer.outputs().getFirst().id(), manual.inputs().getFirst().id()),
                        edge(retry, implementer.inputs().getFirst().id()), edge(proceed, reviewer.inputs().getFirst().id())),
                implementer.inputs().getFirst().id(), reviewer.outputs().getFirst().id()));
        WorkflowRun run = runs.createWorkflowRun(workflow.id(), new CreateWorkflowRunCommand("Implement and review."));
        return new Fixture(run.id(), implementer, manual, reviewer, retry, proceed);
    }

    private NodeRun reachWaiting(Fixture f, int count) {
        worker.poll();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(bySource(f, f.manual())).hasSize(count));
        worker.poll();
        List<NodeRun> waiting = bySource(f, f.manual()).stream().filter(n -> n.status() == NodeRunStatus.WAITING_FOR_MANUAL).toList();
        assertThat(waiting).hasSize(1);
        assertThat(runs.getWorkflowRun(f.runId()).status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(runs.getWorkflowRun(f.runId()).finishedAt()).isNull();
        return waiting.getFirst();
    }

    private void finish(Fixture f, int invocations, int agentExecutions) throws Exception {
        worker.poll();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(runs.getWorkflowRun(f.runId()).status()).isEqualTo(WorkflowRunStatus.SUCCEEDED));
        WorkflowRun result = runs.getWorkflowRun(f.runId());
        assertThat(result.nodeRuns()).hasSize(invocations).allSatisfy(n -> assertThat(n.status()).isEqualTo(NodeRunStatus.SUCCEEDED));
        assertThat(result.finishedAt()).isNotNull();
        assertThat(json.readTree(result.result().jsonValue()).path("review").asText()).isEqualTo("passed");
        assertThat(result.resultSourceNodeRunId()).isEqualTo(bySource(f, f.reviewer()).getFirst().id());
        assertNoManualExecution(f, agentExecutions);
    }

    private void assertNoManualExecution(Fixture f, int agentExecutions) {
        verify(executor, times(agentExecutions)).execute(any());
        verify(workspace, times(agentExecutions)).resolve(any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id=? AND source_node_id=?",
                Long.class, f.runId(), f.manual().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_turns t JOIN node_runs n ON n.id=t.node_run_id WHERE n.workflow_run_id=? AND n.node_type='MANUAL'",
                Long.class, f.runId())).isZero();
        assertThat(bySource(f, f.manual())).allSatisfy(n -> {
            assertThat(n.executionModel()).isNull();
            assertThat(n.contextTrackingVersion()).isNull();
            assertThat(n.sourceAgentId()).isNull();
        });
    }

    private void assertManualDecision(Fixture f, UUID invocation, UUID port, String label) throws Exception {
        NodeRun manual = nodeRuns.findById(invocation).orElseThrow();
        assertThat(manual.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(manual.selectedOutputPortId()).isEqualTo(port);
        assertThat(manual.routingCompletedAt()).isNotNull();
        assertThat(json.readTree(manual.output().jsonValue()).path("selectedOutputName").asText()).isEqualTo(label);
        var resolutions = runs.getWorkflowRun(f.runId()).connectionResolutions().stream()
                .filter(r -> invocation.equals(r.sourceNodeRunId())).toList();
        assertThat(resolutions).hasSize(2);
        assertThat(resolutions).filteredOn(r -> r.type() == ConnectionResolutionType.DELIVERED).singleElement()
                .satisfies(r -> assertThat(r.consumedByNodeRunId()).isNotNull());
        assertThat(resolutions).filteredOn(r -> r.type() == ConnectionResolutionType.CLOSED).hasSize(1);
    }

    private int select(Fixture f, UUID invocation, UUID port) throws Exception {
        return mvc.perform(post("/api/v1/workflow-runs/{run}/node-runs/{node}/manual-selection", f.runId(), invocation)
                .contentType(MediaType.APPLICATION_JSON).content("{\"outputPortId\":\"" + port + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private List<NodeRun> bySource(Fixture f, Node source) {
        return nodeRuns.findByWorkflowRunId(f.runId()).stream().filter(n -> n.sourceNodeId().equals(source.id())).toList();
    }

    private Node node(UUID target, NodeType type, String... outputNames) {
        var outputs = java.util.stream.IntStream.range(0, outputNames.length)
                .mapToObj(i -> new NodePort(UUID.randomUUID(), outputNames[i], outputNames[i] + " output.", i)).toList();
        return new Node(UUID.randomUUID(), target, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(UUID.randomUUID(), "Input", "Input.", 0)), outputs,
                new NodePosition(0, 0), NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null, type);
    }
    private WorkflowConnection edge(UUID from, UUID to) { return new WorkflowConnection(UUID.randomUUID(), from, to); }
    private record Fixture(UUID runId, Node implementer, Node manual, Node reviewer, UUID retry, UUID proceed) {}
}
