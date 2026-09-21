package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspaceResolver;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionWorker;
import com.sitionix.forgeagent.application.runtime.NodeRunWorker;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.WorkflowRunUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@TestPropertySource(properties = "forge.agent.runtime.max-node-runs-per-workflow-run=3")
class ForgeAgentManualBudgetIT {
    @Autowired private ForgeAgentTestManager forgeIt;
    @Autowired private WorkflowUseCases workflows;
    @Autowired private WorkflowRunUseCases runs;
    @Autowired private NodeRunWorker worker;
    @Autowired private NodeRunCompletionWorker completionWorker;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private AgentExecutor executor;
    @MockBean private ExecutionWorkspaceResolver workspaceResolver;

    @Test
    void manualLoopFailsAtExistingExecutionBudgetWithoutCreatingAnotherInvocation() throws Exception {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        Workflow workflow = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Bounded manual loop"));
        Node first = manualNode();
        Node second = manualNode();
        workflows.updateWorkflow(workflow.id(), new SaveWorkflowCommand(workflow.name(), List.of(first, second),
                List.of(new WorkflowConnection(UUID.randomUUID(), first.outputs().getFirst().id(), second.inputs().getFirst().id()),
                        new WorkflowConnection(UUID.randomUUID(), second.outputs().getFirst().id(), first.inputs().getFirst().id())),
                first.inputs().getFirst().id(), second.outputs().getLast().id()));
        UUID runId = runs.createWorkflowRun(workflow.id(), new CreateWorkflowRunCommand("Input")).id();

        worker.poll();
        NodeRun firstVisit = waiting(runId, first.id());
        assertThat(select(runId, firstVisit.id(), first.outputs().getFirst().id())).isEqualTo(200);
        worker.poll();
        NodeRun secondVisit = waiting(runId, second.id());
        assertThat(select(runId, secondVisit.id(), second.outputs().getFirst().id())).isEqualTo(200);
        worker.poll();
        NodeRun repeatedVisit = waiting(runId, first.id());
        assertThat(repeatedVisit.id()).isNotEqualTo(firstVisit.id());
        assertThat(runs.getWorkflowRun(runId).nodeRuns()).hasSize(3);
        assertThat(runs.getWorkflowRun(runId).status()).isEqualTo(WorkflowRunStatus.RUNNING);

        assertThat(select(runId, repeatedVisit.id(), first.outputs().getFirst().id())).isEqualTo(200);
        WorkflowRun exhausted = runs.getWorkflowRun(runId);
        assertThat(exhausted.status()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(exhausted.finishedAt()).isNotNull();
        assertThat(exhausted.nodeRuns()).hasSize(3).allSatisfy(n -> assertThat(n.nodeType()).isEqualTo(NodeType.MANUAL));
        assertThat(exhausted.nodeRuns()).filteredOn(n -> n.id().equals(repeatedVisit.id())).singleElement().satisfies(n -> {
            assertThat(n.status()).isEqualTo(NodeRunStatus.FAILED);
            assertThat(n.failure().code()).isEqualTo("WORKFLOW_EXECUTION_BUDGET_EXCEEDED");
        });
        assertThat(exhausted.nodeRuns()).filteredOn(n -> n.status() == NodeRunStatus.SUCCEEDED).hasSize(2);

        assertThat(select(runId, repeatedVisit.id(), first.outputs().getFirst().id())).isEqualTo(409);
        assertThat(select(runId, firstVisit.id(), first.outputs().getFirst().id())).isEqualTo(200);
        completionWorker.poll();
        worker.poll();
        WorkflowRun afterRetry = runs.getWorkflowRun(runId);
        assertThat(afterRetry.status()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(afterRetry.nodeRuns()).containsExactlyInAnyOrderElementsOf(exhausted.nodeRuns());
        assertThat(afterRetry.connectionResolutions()).containsExactlyInAnyOrderElementsOf(exhausted.connectionResolutions());
        verifyNoInteractions(executor, workspaceResolver);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id=?", Long.class, runId)).isZero();
    }

    private NodeRun waiting(UUID runId, UUID sourceNodeId) {
        var waiting = runs.getWorkflowRun(runId).nodeRuns().stream()
                .filter(n -> n.status() == NodeRunStatus.WAITING_FOR_MANUAL).toList();
        assertThat(waiting).singleElement().satisfies(n -> assertThat(n.sourceNodeId()).isEqualTo(sourceNodeId));
        return waiting.getFirst();
    }

    private int select(UUID runId, UUID nodeRunId, UUID portId) throws Exception {
        return mvc.perform(post("/api/v1/workflow-runs/{run}/node-runs/{node}/manual-selection", runId, nodeRunId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"outputPortId\":\"" + portId + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private Node manualNode() {
        return new Node(UUID.randomUUID(), null, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
                List.of(new NodePort(UUID.randomUUID(), "Retry", "Loop", 0), new NodePort(UUID.randomUUID(), "Done", "Finish", 1)),
                new NodePosition(0, 0), NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null, NodeType.MANUAL);
    }
}
