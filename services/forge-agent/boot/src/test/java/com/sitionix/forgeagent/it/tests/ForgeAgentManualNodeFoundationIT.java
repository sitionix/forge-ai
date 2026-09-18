package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.GET_WORKFLOW_RUN;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class ForgeAgentManualNodeFoundationIT {
    @Autowired private ForgeAgentTestManager forgeIt;
    @Autowired private WorkflowUseCases workflows;
    @Autowired private WorkflowRunUseCases runs;
    @Autowired private CancelWorkflowRunUseCase cancelRun;
    @Autowired private com.sitionix.forgeagent.application.runtime.NodeRunLifecycle agentLifecycle;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void manualTypeAndNullAgentFieldsSurvivePersistenceAndTemplateChanges() {
        Workflow template = manualWorkflow();
        Node node = template.nodes().getFirst();
        Node reloaded = workflowRepository.findById(template.id()).orElseThrow().nodes().getFirst();
        assertThat(reloaded.nodeType()).isEqualTo(NodeType.MANUAL);
        assertThat(reloaded.targetId()).isNull();
        UUID runId = runs.createWorkflowRun(template.id(), new CreateWorkflowRunCommand("Input")).id();

        forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
        UUID agentId = jdbc.queryForObject("SELECT id FROM agent_definitions WHERE project_id = ?", UUID.class, PROJECT_ALPHA_ID);
        Node edited = new Node(node.id(), agentId, node.inputMode(), node.inputs(),
                List.of(new NodePort(node.outputs().getFirst().id(), "Renamed", "Changed template", 0)),
                new NodePosition(300, 400), node.scopeMode(), node.contextMode(), null, NodeType.AGENT);
        workflows.updateWorkflow(template.id(), new SaveWorkflowCommand("Changed", List.of(edited), List.of(),
                template.taskInputPortId(), template.taskOutputPortId()));

        assertThat(workflows.getWorkflow(template.id()).nodes().getFirst().nodeType()).isEqualTo(NodeType.AGENT);
        forgeIt.mockMvc().ping(GET_WORKFLOW_RUN)
                .withPathParameters(PathParams.create().add("runId", runId))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.nodeRuns[0].nodeType").value("MANUAL"))
                .andExpectPath(jsonPath("$.runtimeGraph.nodes[0].nodeType").value("MANUAL"))
                .assertAndCreate();

        WorkflowRun persisted = runs.getWorkflowRun(runId);
        RunNode snapshot = persisted.runtimeGraph().nodes().getFirst();
        assertThat(snapshot.nodeType()).isEqualTo(NodeType.MANUAL);
        assertThat(snapshot.sourceAgentId()).isNull();
        assertThat(snapshot.agentName()).isNull();
        assertThat(snapshot.agentInstructions()).isNull();
        assertThat(snapshot.agentOutputSchema()).isNull();
        assertThat(snapshot.executionModel()).isNull();
        assertThat(snapshot.position()).isEqualTo(new NodePosition(0, 0));
        assertThat(persisted.runtimeGraph().ports()).filteredOn(p -> p.direction() == PortDirection.OUTPUT)
                .extracting(RunPort::name).containsExactly("Continue");
        assertThat(persisted.nodeRuns()).singleElement().satisfies(invocation -> {
            assertThat(invocation.nodeType()).isEqualTo(NodeType.MANUAL);
            assertThat(invocation.sourceAgentId()).isNull();
            assertThat(invocation.agentOutputSchema()).isNull();
            assertThat(invocation.executionModel()).isNull();
            assertThat(invocation.contextTrackingVersion()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id = ?", Long.class, runId)).isZero();
    }

    @Test
    void agentLifecycleNeverStartsManualNodes() {
        Workflow template = manualWorkflow();
        WorkflowRun run = runs.createWorkflowRun(template.id(), new CreateWorkflowRunCommand("Input"));

        assertThat(agentLifecycle.tryStart(run.nodeRuns().getFirst().id())).isEmpty();

        assertThat(runs.getWorkflowRun(run.id()).nodeRuns()).singleElement().satisfies(invocation -> {
            assertThat(invocation.status()).isEqualTo(NodeRunStatus.PENDING);
            assertThat(invocation.failure()).isNull();
            assertThat(invocation.startedAt()).isNull();
        });
    }

    @Test
    void pendingManualRetainsTypeWhenCopiedToCancelledState() {
        Workflow template = manualWorkflow();
        UUID runId = runs.createWorkflowRun(template.id(), new CreateWorkflowRunCommand("Input")).id();

        cancelRun.execute(runId);

        WorkflowRun persisted = runs.getWorkflowRun(runId);
        assertThat(persisted.status()).isEqualTo(WorkflowRunStatus.CANCELLED);
        assertThat(persisted.nodeRuns()).singleElement().satisfies(invocation -> {
            assertThat(invocation.nodeType()).isEqualTo(NodeType.MANUAL);
            assertThat(invocation.status()).isEqualTo(NodeRunStatus.CANCELLED);
            assertThat(invocation.finishedAt()).isNotNull();
        });
    }

    @Test
    void databaseRejectsAgentWithoutTargetAndManualWithAgentMetadata() {
        Workflow template = manualWorkflow();
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_nodes SET node_type = 'AGENT' WHERE workflow_id = ?", template.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
        UUID agentId = jdbc.queryForObject("SELECT id FROM agent_definitions WHERE project_id = ?", UUID.class, PROJECT_ALPHA_ID);
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_nodes SET target_id = ? WHERE workflow_id = ?", agentId, template.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID runId = runs.createWorkflowRun(template.id(), new CreateWorkflowRunCommand("Input")).id();
        for (String table : List.of("workflow_run_nodes", "node_runs")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET agent_name = 'Fake agent' WHERE workflow_run_id = ?", runId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET node_type = 'AGENT' WHERE workflow_run_id = ?", runId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private Workflow manualWorkflow() {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        Workflow template = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Manual flow"));
        UUID input = UUID.randomUUID();
        UUID output = UUID.randomUUID();
        Node node = new Node(UUID.randomUUID(), null, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(input, "Input", "Task input", 0)),
                List.of(new NodePort(output, "Continue", "Continue flow", 0)),
                new NodePosition(0, 0), NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null, NodeType.MANUAL);
        return workflows.updateWorkflow(template.id(), new SaveWorkflowCommand(template.name(), List.of(node), List.of(), input, output));
    }
}
