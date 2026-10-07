package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.*;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class ForgeAgentDialogueFoundationIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired WorkflowUseCases workflows;
    @Autowired WorkflowRunUseCases runs;
    @Autowired JdbcTemplate jdbc;

    @Test void dialogueDispositionRoundTripsThroughSnapshot() {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
        Workflow w = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Dialogue foundation"));
        NodePort input = new NodePort(UUID.randomUUID(), "Input", "Task context", 0);
        NodePort output = new NodePort(UUID.randomUUID(), "Accept", "Reviewed result", 0, DialogueOutputDisposition.ACCEPT);
        Node n = new Node(UUID.randomUUID(), AGENT_A_ID, NodeInputMode.TASK_AND_DEPENDENCIES,
                List.of(input), List.of(output), new NodePosition(0, 0), NodeScopeMode.GLOBAL,
                NodeContextMode.FRESH_EACH_NODE_RUN, null, NodeType.DIALOGUE);
        workflows.updateWorkflow(w.id(), new SaveWorkflowCommand(w.name(), List.of(n), List.of(), input.id(), output.id()));
        assertThat(workflows.getWorkflow(w.id()).nodes().getFirst().outputs().getFirst().dialogueDisposition())
                .isEqualTo(DialogueOutputDisposition.ACCEPT);
        WorkflowRun r = runs.createWorkflowRun(w.id(), new CreateWorkflowRunCommand("Clarify this task"));
        WorkflowRun loaded = runs.getWorkflowRun(r.id());
        assertThat(loaded.runtimeGraph().ports()).filteredOn(p -> p.direction() == PortDirection.OUTPUT)
                .extracting(RunPort::dialogueDisposition).containsExactly(DialogueOutputDisposition.ACCEPT);
        assertThat(loaded.nodeRuns()).singleElement().satisfies(node -> {
            assertThat(node.nodeType()).isEqualTo(NodeType.DIALOGUE);
            assertThat(node.contextMode()).isEqualTo(NodeContextMode.DIALOGUE_WITHIN_NODE_RUN);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_turns t JOIN node_runs n ON n.id=t.node_run_id WHERE n.workflow_run_id=?", Long.class, r.id())).isZero();
    }
}
