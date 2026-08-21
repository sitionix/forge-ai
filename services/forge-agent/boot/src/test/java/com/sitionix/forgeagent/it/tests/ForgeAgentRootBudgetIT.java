package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.WorkflowRunUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ExecutionFrameEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunPortEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "forge.agent.runtime.max-node-runs-per-workflow-run=1")
class ForgeAgentRootBudgetIT {

    private static final UUID A = UUID.fromString("95000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("95000000-0000-4000-8000-000000000002");
    private static final UUID A_IN = UUID.fromString("95200000-0000-4000-8000-000000000001");
    private static final UUID B_IN = UUID.fromString("95200000-0000-4000-8000-000000000002");
    private static final UUID A_OUT = UUID.fromString("95100000-0000-4000-8000-000000000001");
    private static final UUID B_OUT = UUID.fromString("95100000-0000-4000-8000-000000000002");
    private static final UUID A_TO_B = UUID.fromString("95300000-0000-4000-8000-000000000001");

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private WorkflowUseCases workflowUseCases;
    @Autowired
    private WorkflowRunUseCases workflowRunUseCases;

    @Test
    void explicitTaskInputCreatesOnlyOneRootNodeRunWithinBudget() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json"))
                .to(WORKFLOW.withJson("workflow_alpha.json"))
                .build();
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        this.node(A, AGENT_A_ID, A_OUT, 0),
                        this.node(B, AGENT_B_ID, B_OUT, 1)
                ),
                List.of(new WorkflowConnection(A_TO_B, A_OUT, B_IN)),
                A_IN,
                B_OUT
        ));

        this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("One explicit root."));

        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).hasSize(1);
        assertThat(this.forgeIt.postgresql().get(ExecutionFrameEntity.class).getAll()).hasSize(1);
        assertThat(this.forgeIt.postgresql().get(WorkflowRunNodeEntity.class).getAll()).hasSize(2);
        assertThat(this.forgeIt.postgresql().get(WorkflowRunPortEntity.class).getAll()).hasSize(4);
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).hasSize(1);
    }

    private Node node(final UUID id, final UUID agentId, final UUID outputPortId, final int x) {
        return new Node(
                id,
                agentId,
                NodeInputMode.DEPENDENCIES_ONLY,
                id.equals(A)
                        ? List.of(new NodePort(A_IN, "Input", "Input description.", 0))
                        : List.of(new NodePort(B_IN, "Input", "Input description.", 0)),
                List.of(new NodePort(outputPortId, "Done", "Done description.", 0)),
                new NodePosition(x * 100.0, 0.0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        );
    }
}
