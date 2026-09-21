package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.ForgeAgentApplication;
import com.sitionix.forgeagent.application.runtime.ManualNodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionWorker;
import com.sitionix.forgeagent.application.runtime.NodeRunWorker;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** Restarts all application beans and their database connections against the same PostgreSQL database. */
class ForgeAgentManualRestartIT {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startDatabase() {
        DATABASE.start();
    }

    @AfterAll
    static void stopDatabase() {
        DATABASE.stop();
    }

    @ParameterizedTest(name = "restart after selection committed = {0}")
    @ValueSource(booleans = {false, true})
    void fullApplicationRestartPreservesManualWaitAndRecoversUnroutedDecision(boolean decisionCommitted) throws Exception {
        Fixture fixture;
        NodeRun persisted;
        NodeRunRepository originalRepository;
        ServletWebServerApplicationContext original = startApplication();
        try (original) {
            fixture = createRun(original);
            original.getBean(NodeRunWorker.class).poll();
            originalRepository = original.getBean(NodeRunRepository.class);
            assertThat(originalRepository.findById(fixture.invocation()).orElseThrow().status())
                    .isEqualTo(NodeRunStatus.WAITING_FOR_MANUAL);
            if (decisionCommitted) {
                // Commit the decision in its production transaction, stopping before the separate routing step.
                original.getBean(ManualNodeRunLifecycle.class)
                        .selectOutput(fixture.runId(), fixture.invocation(), fixture.continuePort());
            }
            persisted = originalRepository.findById(fixture.invocation()).orElseThrow();
            assertThat(persisted.routingCompletedAt()).isNull();
            assertThat(originalRepository.findByWorkflowRunId(fixture.runId())).hasSize(1);
        }
        assertThat(original.isActive()).isFalse();

        try (ServletWebServerApplicationContext restarted = startApplication()) {
            NodeRunRepository repository = restarted.getBean(NodeRunRepository.class);
            assertThat(restarted).isNotSameAs(original);
            assertThat(repository).isNotSameAs(originalRepository);
            assertThat(repository.findById(fixture.invocation()).orElseThrow()).isEqualTo(persisted);
            var runs = restarted.getBean(WorkflowRunUseCases.class);
            var worker = restarted.getBean(NodeRunWorker.class);
            var completion = restarted.getBean(NodeRunCompletionWorker.class);
            worker.poll();
            completion.poll();

            if (!decisionCommitted) {
                assertThat(repository.findById(fixture.invocation()).orElseThrow()).isEqualTo(persisted);
                WorkflowRun waiting = runs.getWorkflowRun(fixture.runId());
                assertThat(waiting.status()).isEqualTo(WorkflowRunStatus.RUNNING);
                assertThat(waiting.finishedAt()).isNull();
                assertThat(waiting.nodeRuns()).hasSize(1);
                assertThat(waiting.executionEdges()).isEmpty();
            } else {
                assertThat(repository.findById(fixture.invocation()).orElseThrow().routingCompletedAt()).isNotNull();
                assertThat(repository.findByWorkflowRunId(fixture.runId())).hasSize(2);
            }

            // In the recovery case this is also an HTTP retry of the pre-restart committed decision.
            select(restarted, fixture.runId(), fixture.invocation(), fixture.continuePort());
            select(restarted, fixture.runId(), fixture.invocation(), fixture.continuePort());
            completion.poll();
            WorkflowRun routed = runs.getWorkflowRun(fixture.runId());
            assertThat(routed.nodeRuns()).hasSize(2);
            NodeRun routedChild = routed.nodeRuns().stream()
                    .filter(node -> node.sourceNodeId().equals(fixture.child().id())).findFirst().orElseThrow();
            assertThat(routed.connectionResolutions()).singleElement().satisfies(resolution -> {
                assertThat(resolution.type()).isEqualTo(ConnectionResolutionType.DELIVERED);
                assertThat(resolution.sourceNodeRunId()).isEqualTo(fixture.invocation());
                assertThat(resolution.consumedByNodeRunId()).isEqualTo(routedChild.id());
            });
            NodeRun source = repository.findById(fixture.invocation()).orElseThrow();
            assertThat(source.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
            assertThat(source.selectedOutputPortId()).isEqualTo(fixture.continuePort());
            assertThat(source.routingCompletedAt()).isNotNull();

            worker.poll();
            NodeRun child = repository.findByWorkflowRunId(fixture.runId()).stream()
                    .filter(node -> node.sourceNodeId().equals(fixture.child().id())).findFirst().orElseThrow();
            assertThat(child.status()).isEqualTo(NodeRunStatus.WAITING_FOR_MANUAL);
            select(restarted, fixture.runId(), child.id(), fixture.child().outputs().getFirst().id());
            WorkflowRun completed = runs.getWorkflowRun(fixture.runId());
            assertThat(completed.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
            assertThat(completed.finishedAt()).isNotNull();
            assertThat(completed.resultSourceNodeRunId()).isEqualTo(child.id());
            assertThat(completed.nodeRuns()).hasSize(2).allSatisfy(node ->
                    assertThat(node.status()).isEqualTo(NodeRunStatus.SUCCEEDED));
            assertThat(restarted.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id=?", Long.class, fixture.runId()))
                    .isZero();
        }
    }

    private static ServletWebServerApplicationContext startApplication() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ForgeAgentApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + DATABASE.getJdbcUrl(),
                "--spring.datasource.username=" + DATABASE.getUsername(),
                "--spring.datasource.password=" + DATABASE.getPassword(),
                "--forge.agent.worker.scheduling-enabled=false",
                "--spring.main.banner-mode=off");
    }

    private static void select(ServletWebServerApplicationContext context, UUID run, UUID invocation, UUID port)
            throws Exception {
        URI uri = URI.create("http://localhost:" + context.getWebServer().getPort()
                + "/api/v1/workflow-runs/" + run + "/node-runs/" + invocation + "/manual-selection");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"outputPortId\":\"" + port + "\"}")).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
    }

    private static Fixture createRun(ServletWebServerApplicationContext context) {
        Project project = context.getBean(ProjectUseCases.class)
                .createProject(new CreateProjectCommand("Manual restart " + UUID.randomUUID()));
        var workflows = context.getBean(WorkflowUseCases.class);
        Workflow workflow = workflows.createWorkflow(project.id(), new CreateWorkflowCommand("Restart"));
        Node root = manualNode();
        Node child = manualNode();
        workflows.updateWorkflow(workflow.id(), new SaveWorkflowCommand(workflow.name(), List.of(root, child),
                List.of(new WorkflowConnection(UUID.randomUUID(), root.outputs().getFirst().id(), child.inputs().getFirst().id())),
                root.inputs().getFirst().id(), child.outputs().getFirst().id()));
        WorkflowRun run = context.getBean(WorkflowRunUseCases.class)
                .createWorkflowRun(workflow.id(), new CreateWorkflowRunCommand("Restart input"));
        return new Fixture(run.id(), run.nodeRuns().getFirst().id(), root.outputs().getFirst().id(), child);
    }

    private static Node manualNode() {
        return new Node(UUID.randomUUID(), null, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
                List.of(new NodePort(UUID.randomUUID(), "Continue", "Continue", 0)), new NodePosition(0, 0),
                NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null, NodeType.MANUAL);
    }

    private record Fixture(UUID runId, UUID invocation, UUID continuePort, Node child) {}
}
