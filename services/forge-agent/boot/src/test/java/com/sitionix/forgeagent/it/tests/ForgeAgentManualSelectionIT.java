package com.sitionix.forgeagent.it.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@IntegrationTest
class ForgeAgentManualSelectionIT {
    @Autowired private ForgeAgentTestManager forgeIt;
    @Autowired private WorkflowUseCases workflows;
    @Autowired private WorkflowRunUseCases runs;
    @Autowired private CancelWorkflowRunUseCase cancel;
    @Autowired private NodeRunWorker worker;
    @Autowired private NodeRunRepository nodeRuns;
    @Autowired private ManualNodeRunLifecycle manualLifecycle;
    @Autowired private NodeRunCompletionWorker completionWorker;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private AgentExecutor executor;
    @MockBean private ExecutionWorkspaceResolver workspaceResolver;

    @Test
    void selectionUsesExistingRoutingAndRepeatedRequestDoesNotActivateTwice() throws Exception {
        Fixture f = fixture(false);
        worker.poll();
        assertThat(select(f, f.retry())).isEqualTo(200);
        NodeRun selected = nodeRuns.findById(f.invocation()).orElseThrow();
        assertThat(selected.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(selected.selectedOutputPortId()).isEqualTo(f.retry());
        assertThat(selected.routingCompletedAt()).isNotNull();
        assertThat(selected.finishedAt()).isNotNull();
        assertThat(json.readTree(selected.output().jsonValue()).path("selectedOutputName").asText()).isEqualTo("Retry \"later\"");
        assertThat(json.readTree(selected.output().jsonValue()).path("selectedOutputPortId").asText()).isEqualTo(f.retry().toString());
        assertThat(select(f, f.retry())).isEqualTo(200);
        assertThat(select(f, f.skip())).isEqualTo(409);
        assertThat(nodeRuns.findById(f.invocation()).orElseThrow()).isEqualTo(selected);
        assertRoutedOnce(f);
        verifyNoInteractions(executor, workspaceResolver);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id=?", Long.class, f.runId())).isZero();
    }

    @Test
    void invalidPortsAndWrongRunDoNotChangeWaitingInvocation() throws Exception {
        Fixture f = fixture(false);
        worker.poll();
        assertThat(select(f, UUID.randomUUID())).isEqualTo(400);
        assertThat(select(f, f.root().inputs().getFirst().id())).isEqualTo(400);
        assertThat(select(f, f.child().outputs().getFirst().id())).isEqualTo(400);
        WorkflowRun other = runs.createWorkflowRun(f.workflow().id(), new CreateWorkflowRunCommand("Other"));
        assertThat(select(other.id(), f.invocation(), f.retry())).isEqualTo(404);
        assertThat(nodeRuns.findById(f.invocation()).orElseThrow().status()).isEqualTo(NodeRunStatus.WAITING_FOR_MANUAL);
        assertThat(nodeRuns.findByWorkflowRunId(f.runId())).hasSize(1);
    }

    @Test
    void pendingAndCancelledManualCannotBeSelected() throws Exception {
        Fixture f = fixture(false);
        assertThat(select(f, f.retry())).isEqualTo(409);
        worker.poll();
        cancel.execute(f.runId());
        assertThat(select(f, f.retry())).isEqualTo(409);
        assertThat(nodeRuns.findById(f.invocation()).orElseThrow().status()).isEqualTo(NodeRunStatus.CANCELLED);
    }

    @Test
    void agentNodeCannotBeSelected() throws Exception {
        Fixture f = fixture(true);
        assertThat(select(f, f.retry())).isEqualTo(409);
        assertThat(nodeRuns.findById(f.invocation()).orElseThrow().status()).isEqualTo(NodeRunStatus.PENDING);
        verifyNoInteractions(executor, workspaceResolver);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentSelectionsSerializeAndRouteOnce(boolean samePort) throws Exception {
        Fixture f = fixture(false);
        worker.poll();
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { start.await(); return select(f, f.retry()); });
            var second = threads.submit(() -> { start.await(); return select(f, samePort ? f.retry() : f.skip()); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, samePort ? 200 : 409);
        }
        assertRoutedOnce(f);
    }

    @Test
    void immutableSnapshotSuppliesAllowedPortsAndAuditName() throws Exception {
        Fixture f = fixture(false);
        worker.poll();
        Node replacement = node(null, NodeType.MANUAL, false);
        workflows.updateWorkflow(f.workflow().id(), new SaveWorkflowCommand(f.workflow().name(), List.of(replacement),
                List.of(), replacement.inputs().getFirst().id(), replacement.outputs().getFirst().id()));
        assertThat(select(f, replacement.outputs().getFirst().id())).isEqualTo(400);
        assertThat(select(f, f.retry())).isEqualTo(200);
        assertRoutedOnce(f);
        assertThat(json.readTree(nodeRuns.findById(f.invocation()).orElseThrow().output().jsonValue())
                .path("selectedOutputName").asText()).isEqualTo("Retry \"later\"");
    }

    @Test
    void manualOutputCanCompleteWorkflowAndSelectionRemainsIdempotentAfterCompletion() throws Exception {
        Fixture f = fixture(false);
        worker.poll();
        assertThat(select(f, f.skip())).isEqualTo(200);
        worker.poll();
        NodeRun child = nodeRuns.findByWorkflowRunId(f.runId()).stream()
                .filter(n -> n.sourceNodeId().equals(f.child().id())).findFirst().orElseThrow();
        UUID done = f.child().outputs().getFirst().id();
        assertThat(select(f.runId(), child.id(), done)).isEqualTo(200);
        WorkflowRun completed = runs.getWorkflowRun(f.runId());
        assertThat(completed.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(completed.resultSourceNodeRunId()).isEqualTo(child.id());
        assertThat(select(f.runId(), child.id(), done)).isEqualTo(200);
        assertThat(runs.getWorkflowRun(f.runId()).status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
    }

    @Test
    void completionWorkerRecoversDecisionCommittedBeforeRouting() {
        Fixture f = fixture(false);
        worker.poll();
        manualLifecycle.selectOutput(f.runId(), f.invocation(), f.retry());
        NodeRun persisted = nodeRuns.findById(f.invocation()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(persisted.selectedOutputPortId()).isEqualTo(f.retry());
        assertThat(persisted.routingCompletedAt()).isNull();
        assertThat(nodeRuns.findByWorkflowRunId(f.runId())).hasSize(1);
        completionWorker.poll();
        completionWorker.poll();
        assertRoutedOnce(f);
        assertThat(nodeRuns.findById(f.invocation()).orElseThrow().routingCompletedAt()).isNotNull();
    }

    @Test
    void cancellationAfterSelectionCommitPreventsRoutingEvenOnHttpRetry() throws Exception {
        Fixture f = fixture(false);
        worker.poll();
        manualLifecycle.selectOutput(f.runId(), f.invocation(), f.retry());
        cancel.execute(f.runId());
        completionWorker.poll();
        assertThat(select(f, f.retry())).isEqualTo(200);
        WorkflowRun stopped = runs.getWorkflowRun(f.runId());
        assertThat(stopped.status()).isEqualTo(WorkflowRunStatus.CANCELLED);
        assertThat(stopped.nodeRuns()).hasSize(1);
        assertThat(stopped.executionEdges()).isEmpty();
        verifyNoInteractions(executor, workspaceResolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"outputPortId\":null}", "{\"outputPortId\":\"invalid\"}"})
    void malformedSelectionBodyIsRejected(String body) throws Exception {
        Fixture f = fixture(false);
        worker.poll();
        assertThat(mvc.perform(post("/api/v1/workflow-runs/{run}/node-runs/{node}/manual-selection", f.runId(), f.invocation())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(nodeRuns.findById(f.invocation()).orElseThrow().status()).isEqualTo(NodeRunStatus.WAITING_FOR_MANUAL);
    }

    private void assertRoutedOnce(Fixture f) {
        WorkflowRun run = runs.getWorkflowRun(f.runId());
        assertThat(run.nodeRuns()).hasSize(2);
        assertThat(run.connectionResolutions()).hasSize(2);
        NodeRun source = nodeRuns.findById(f.invocation()).orElseThrow();
        UUID selectedConnection = run.runtimeGraph().connections().stream()
                .filter(c -> c.sourceOutputPortId().equals(source.selectedOutputPortId()))
                .findFirst().orElseThrow().sourceConnectionId();
        assertThat(run.connectionResolutions()).filteredOn(r -> r.type() == ConnectionResolutionType.DELIVERED)
                .singleElement().satisfies(r -> {
                    assertThat(r.sourceConnectionId()).isEqualTo(selectedConnection);
                    assertThat(r.sourceNodeRunId()).isEqualTo(f.invocation());
                    assertThat(r.consumedByNodeRunId()).isNotNull();
                });
        assertThat(run.connectionResolutions()).filteredOn(r -> r.type() == ConnectionResolutionType.CLOSED).hasSize(1);
        assertThat(run.nodeRuns()).filteredOn(n -> n.sourceNodeId().equals(f.child().id())).singleElement()
                .satisfies(n -> assertThat(n.status()).isEqualTo(NodeRunStatus.PENDING));
    }

    private int select(Fixture f, UUID port) throws Exception { return select(f.runId(), f.invocation(), port); }

    private int select(UUID run, UUID invocation, UUID port) throws Exception {
        var response = mvc.perform(post("/api/v1/workflow-runs/{run}/node-runs/{node}/manual-selection", run, invocation)
                .contentType(MediaType.APPLICATION_JSON).content("{\"outputPortId\":\"" + port + "\"}"))
                .andReturn().getResponse();
        if (response.getStatus() == 200) {
            var body = json.readTree(response.getContentAsString());
            assertThat(body.path("id").asText()).isEqualTo(run.toString());
            assertThat(body.path("nodeRuns").isArray()).isTrue();
            var selected = java.util.stream.StreamSupport.stream(body.path("nodeRuns").spliterator(), false)
                    .filter(n -> n.path("id").asText().equals(invocation.toString())).findFirst().orElseThrow();
            assertThat(selected.path("nodeType").asText()).isEqualTo("MANUAL");
            assertThat(selected.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(selected.path("selectedOutputPortId").asText()).isEqualTo(port.toString());
        }
        return response.getStatus();
    }

    private Fixture fixture(boolean agent) {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        UUID target = null;
        if (agent) {
            forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
            target = jdbc.queryForObject("SELECT id FROM agent_definitions WHERE project_id=?", UUID.class, PROJECT_ALPHA_ID);
        }
        Workflow workflow = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Manual selection"));
        Node root = node(target, agent ? NodeType.AGENT : NodeType.MANUAL, true);
        Node child = node(null, NodeType.MANUAL, false);
        List<WorkflowConnection> connections = root.outputs().stream().map(p ->
                new WorkflowConnection(UUID.randomUUID(), p.id(), child.inputs().getFirst().id())).toList();
        workflows.updateWorkflow(workflow.id(), new SaveWorkflowCommand(workflow.name(), List.of(root, child), connections,
                root.inputs().getFirst().id(), child.outputs().getFirst().id()));
        WorkflowRun run = runs.createWorkflowRun(workflow.id(), new CreateWorkflowRunCommand("Input"));
        return new Fixture(workflow, root, child, run.id(), run.nodeRuns().getFirst().id(),
                root.outputs().getFirst().id(), root.outputs().getLast().id());
    }

    private Node node(UUID target, NodeType type, boolean branching) {
        var output = new NodePort(UUID.randomUUID(), branching ? "Retry \"later\"" : "Done", "Output", 0);
        return new Node(UUID.randomUUID(), target, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
                branching ? List.of(output, new NodePort(UUID.randomUUID(), "Skip", "Skip", 1)) : List.of(output),
                new NodePosition(0, 0), NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null, type);
    }

    private record Fixture(Workflow workflow, Node root, Node child, UUID runId, UUID invocation, UUID retry, UUID skip) {}
}
