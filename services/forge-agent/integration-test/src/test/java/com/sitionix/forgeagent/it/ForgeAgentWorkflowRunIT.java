package com.sitionix.forgeagent.it;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.OTHER_PROJECT_AGENT_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_BETA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createWorkflowRun;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createWorkflowRunError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.getWorkflowRun;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.listWorkflowRuns;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.updateAgent;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW_NODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentWorkflowRunIT {

    private static final UUID NODE_D_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenWorkflowGraph_whenCreateAndInspectRun_thenSnapshotIsPersistedAndRoundTrips() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow("requestUpdateWorkflowGraph.json");

        final WorkflowRunEntity created = this.createRun();

        assertThat(created.getStatus()).isEqualTo("QUEUED");
        assertThat(created.getInput()).isEqualTo("Review the authentication service changes.");
        assertThat(created.getWorkflowName()).isEqualTo("Full Testing");
        assertThat(this.nodeRuns(created.getId())).hasSize(3).allSatisfy(nodeRun -> {
            assertThat(nodeRun.getStatus()).isEqualTo("PENDING");
            assertThat(nodeRun.getOutput()).isNull();
            assertThat(nodeRun.getFailureCode()).isNull();
            assertThat(nodeRun.getFailureMessage()).isNull();
            assertThat(nodeRun.getStartedAt()).isNull();
            assertThat(nodeRun.getFinishedAt()).isNull();
        });

        this.forgeIt.mockMvc()
                .ping(getWorkflowRun())
                .withPathParameters(PathParams.create().add("runId", created.getId()))
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll())
                .singleElement()
                .satisfies(entity -> {
                    assertThat(entity.getId()).isEqualTo(created.getId());
                    assertThat(entity.getSourceWorkflowId()).isEqualTo(WORKFLOW_ID);
                    assertThat(entity.getWorkflowName()).isEqualTo("Full Testing");
                    assertThat(entity.getInput()).isEqualTo("Review the authentication service changes.");
                    assertThat(entity.getStatus()).isEqualTo("QUEUED");
                });
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).hasSize(3);
    }

    @Test
    void givenDefinitionsChangeAfterRun_whenRunIsRead_thenOldSnapshotRemainsImmutableAndNewRunUsesNewDefinition() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow("requestUpdateWorkflowGraph.json");

        final WorkflowRunEntity runOne = this.createRun();

        this.forgeIt.mockMvc()
                .ping(updateAgent())
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .withRequest("requestUpdateAgent.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
        this.updateWorkflow("requestUpdateWorkflowRenamedMovedWithNewNode.json");

        this.forgeIt.mockMvc()
                .ping(getWorkflowRun())
                .withPathParameters(PathParams.create().add("runId", runOne.getId()))
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
        assertThat(runOne.getWorkflowName()).isEqualTo("Full Testing");
        assertThat(this.nodeRuns(runOne.getId())).hasSize(3);
        assertThat(this.nodeRun(runOne.getId(), NODE_A_ID).getAgentName()).isEqualTo("Agent A");
        assertThat(this.nodeRun(runOne.getId(), NODE_A_ID).getAgentInstructions()).isEqualTo("Do work for Agent A.");
        assertThat(this.nodeRun(runOne.getId(), NODE_A_ID).getAgentOutputSchema()).isEqualToIgnoringWhitespace("{\"type\":\"object\"}");
        assertThat(this.nodeRun(runOne.getId(), NODE_A_ID).getPositionX()).isEqualTo(120.0);
        assertThat(this.nodeRun(runOne.getId(), NODE_A_ID).getPositionY()).isEqualTo(100.0);

        final WorkflowRunEntity runTwo = this.createRun();
        assertThat(runTwo.getWorkflowName()).isEqualTo("Full Verification");
        assertThat(this.nodeRuns(runTwo.getId())).hasSize(2);
        assertThat(this.nodeRun(runTwo.getId(), NODE_A_ID).getAgentName()).isEqualTo("Analyzer Updated");
        assertThat(this.nodeRun(runTwo.getId(), NODE_A_ID).getAgentInstructions()).isEqualTo("Analyze project context with updated instructions.");
        assertThat(this.nodeRun(runTwo.getId(), NODE_A_ID).getAgentOutputSchema())
                .isEqualToIgnoringWhitespace("{\"type\":\"object\",\"properties\":{\"updated\":{\"type\":\"boolean\"}}}");
        assertThat(this.nodeRun(runTwo.getId(), NODE_A_ID).getPositionX()).isEqualTo(900.0);
        assertThat(this.nodeRun(runTwo.getId(), NODE_A_ID).getPositionY()).isEqualTo(910.0);
    }

    @Test
    void givenFanInWorkflow_whenCreateRun_thenDependenciesUseNodeRunIds() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow("requestUpdateWorkflowFanInGraph.json");

        final WorkflowRunEntity run = this.createRun();

        final List<NodeRunEntity> nodeRuns = this.nodeRuns(run.getId());
        final Map<UUID, UUID> nodeRunIdBySourceNodeId = nodeRuns.stream()
                .collect(Collectors.toMap(NodeRunEntity::getSourceNodeId, NodeRunEntity::getId));
        assertThat(this.nodeRun(run.getId(), NODE_B_ID).getDependsOnNodeRunIds()).containsExactly(nodeRunIdBySourceNodeId.get(NODE_A_ID));
        assertThat(this.nodeRun(run.getId(), NODE_C_ID).getDependsOnNodeRunIds()).containsExactly(nodeRunIdBySourceNodeId.get(NODE_A_ID));
        assertThat(this.nodeRun(run.getId(), NODE_D_ID).getDependsOnNodeRunIds())
                .containsExactly(nodeRunIdBySourceNodeId.get(NODE_B_ID), nodeRunIdBySourceNodeId.get(NODE_C_ID));
        assertThat(nodeRuns)
                .flatExtracting(nodeRun -> Arrays.asList(nodeRun.getDependsOnNodeRunIds()))
                .doesNotContain(NODE_A_ID, NODE_B_ID, NODE_C_ID, NODE_D_ID);
    }

    @Test
    void givenSameAgentInMultipleNodes_whenCreateRun_thenDistinctNodeRunsAreCreated() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow("requestUpdateWorkflowGraph.json");

        final WorkflowRunEntity run = this.createRun();

        final var first = this.nodeRun(run.getId(), NODE_A_ID);
        final var repeated = this.nodeRun(run.getId(), NODE_C_ID);
        assertThat(first.getSourceAgentId()).isEqualTo(AGENT_A_ID);
        assertThat(repeated.getSourceAgentId()).isEqualTo(AGENT_A_ID);
        assertThat(first.getId()).isNotEqualTo(repeated.getId());
        assertThat(first.getSourceNodeId()).isNotEqualTo(repeated.getSourceNodeId());
    }

    @Test
    void givenCorruptCrossProjectNodeTarget_whenCreateRunFails_thenNoPartialSnapshotIsPersisted() {
        this.seedTwoProjectsAgentsAndWorkflow();
        final WorkflowNodeEntity corruptNode = new WorkflowNodeEntity();
        corruptNode.setWorkflowId(WORKFLOW_ID);
        corruptNode.setId(NODE_A_ID);
        corruptNode.setTargetId(OTHER_PROJECT_AGENT_ID);
        corruptNode.setDependsOnNodeIds(new UUID[0]);
        corruptNode.setPositionX(1.0);
        corruptNode.setPositionY(2.0);
        this.forgeIt.postgresql().create().to(WORKFLOW_NODE.withEntity(corruptNode)).build();

        this.forgeIt.mockMvc()
                .ping(createWorkflowRunError())
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestCreateWorkflowRun.json")
                .expectStatus(HttpStatus.CONFLICT)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenMultipleRuns_whenListHistory_thenNewestFirstWithDeterministicTieBreaker() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow("requestUpdateWorkflowGraph.json");

        final WorkflowRunEntity first = this.createRun();
        final WorkflowRunEntity second = this.createRun();

        this.forgeIt.mockMvc()
                .ping(listWorkflowRuns())
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        final List<WorkflowRunEntity> persisted = this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.stream()
                .sorted(Comparator.comparing(WorkflowRunEntity::getCreatedAt)
                        .thenComparing(WorkflowRunEntity::getId)
                        .reversed())
                .map(WorkflowRunEntity::getId)
                .toList())
                .containsExactlyElementsOf(List.of(first, second).stream()
                        .sorted(Comparator.comparing(WorkflowRunEntity::getCreatedAt)
                                .thenComparing(WorkflowRunEntity::getId)
                                .reversed())
                        .map(WorkflowRunEntity::getId)
                        .toList());
        assertThat(persisted).extracting(WorkflowRunEntity::getStartedAt).containsOnlyNulls();
        assertThat(persisted).extracting(WorkflowRunEntity::getFinishedAt).containsOnlyNulls();
    }

    private WorkflowRunEntity createRun() {
        this.forgeIt.mockMvc()
                .ping(createWorkflowRun())
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestCreateWorkflowRun.json")
                .expectStatus(HttpStatus.CREATED)
                .andExpectPath(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/workflow-runs/")))
                .assertAndCreate();
        return this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll().stream()
                .max(Comparator.comparing(WorkflowRunEntity::getCreatedAt).thenComparing(WorkflowRunEntity::getId))
                .orElseThrow();
    }

    private void updateWorkflow(final String requestFixture) {
        this.forgeIt.mockMvc()
                .ping(ForgeAgentMockMvcEndpoint.updateWorkflow())
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest(requestFixture)
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
    }

    private List<NodeRunEntity> nodeRuns(final UUID workflowRunId) {
        return this.forgeIt.postgresql().get(NodeRunEntity.class).getAll().stream()
                .filter(nodeRun -> workflowRunId.equals(nodeRun.getWorkflowRunId()))
                .toList();
    }

    private NodeRunEntity nodeRun(final UUID workflowRunId, final UUID sourceNodeId) {
        return this.nodeRuns(workflowRunId).stream()
                .filter(nodeRun -> sourceNodeId.equals(nodeRun.getSourceNodeId()))
                .findFirst()
                .orElseThrow();
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

    private void seedTwoProjectsAgentsAndWorkflow() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(PROJECT.withJson("project_beta.json"))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json"))
                .to(AGENT_DEFINITION.withJson("agent_c.json"))
                .to(AGENT_DEFINITION.withJson("other_project_agent.json"))
                .to(WORKFLOW.withJson("workflow_alpha.json"))
                .build();
    }
}
