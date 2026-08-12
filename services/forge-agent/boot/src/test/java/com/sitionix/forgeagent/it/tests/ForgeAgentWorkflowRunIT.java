package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.OTHER_PROJECT_AGENT_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_BETA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_WORKFLOW_RUN;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_WORKFLOW_RUN_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.GET_WORKFLOW_RUN;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_WORKFLOW_RUNS;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_AGENT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW_NODE;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW_RUN;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
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
        this.UPDATE_WORKFLOW("requestUpdateWorkflowGraph.json");

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

        this.assertGetThreeNodeGraphSnapshot(created.getId());

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
        this.UPDATE_WORKFLOW("requestUpdateWorkflowGraph.json");

        final WorkflowRunEntity runOne = this.createRun();

        this.forgeIt.mockMvc()
                .ping(UPDATE_AGENT)
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .withRequest("requestUpdateAgent.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
        this.UPDATE_WORKFLOW("requestUpdateWorkflowRenamedMovedWithNewNode.json");

        this.assertGetThreeNodeGraphSnapshot(runOne.getId());
        assertThat(this.workflowRun(runOne.getId()).getWorkflowName()).isEqualTo("Full Testing");
        assertThat(this.nodeRuns(runOne.getId())).hasSize(3);
        assertThat(this.nodeRun(runOne.getId(), NODE_A_ID).getAgentInstructions()).isEqualTo("Do work for Agent A.");

        final WorkflowRunEntity runTwo = this.createRun("Full Verification", 2);
        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW_RUN)
                .withPathParameters(PathParams.create().add("runId", runTwo.getId()))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.id").value(runTwo.getId().toString()))
                .andExpectPath(jsonPath("$.workflowName").value("Full Verification"))
                .andExpectPath(jsonPath("$.nodeRuns", hasSize(2)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentName")).value(contains("Analyzer Updated")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentInstructions")).value(contains("Analyze project context with updated instructions.")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentOutputSchema.properties.updated.type")).value(contains("boolean")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "position.x")).value(contains(900.0)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "position.y")).value(contains(910.0)))
                .assertAndCreate();
        assertThat(this.workflowRun(runTwo.getId()).getWorkflowName()).isEqualTo("Full Verification");
        assertThat(this.nodeRuns(runTwo.getId())).hasSize(2);
        assertThat(this.nodeRun(runTwo.getId(), NODE_A_ID).getAgentOutputSchema())
                .isEqualToIgnoringWhitespace("{\"type\":\"object\",\"properties\":{\"updated\":{\"type\":\"boolean\"}}}");
    }

    @Test
    void givenFanInWorkflow_whenCreateRun_thenDependenciesUseNodeRunIds() {
        this.seedProjectAgentsAndWorkflow();
        this.UPDATE_WORKFLOW("requestUpdateWorkflowFanInGraph.json");

        final WorkflowRunEntity run = this.createRun("Full Testing", 4);

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
    void givenEmptyWorkflow_whenCreateRun_thenConflictAndNoSnapshotIsPersisted() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_WORKFLOW_RUN_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestCreateWorkflowRun.json")
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("EMPTY_WORKFLOW"))
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenSameAgentInMultipleNodes_whenCreateRun_thenDistinctNodeRunsAreCreated() {
        this.seedProjectAgentsAndWorkflow();
        this.UPDATE_WORKFLOW("requestUpdateWorkflowGraph.json");

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
                .ping(CREATE_WORKFLOW_RUN_ERROR)
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
        final UUID lowerRunId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        final UUID higherRunId = UUID.fromString("50000000-0000-4000-8000-000000000002");
        final Instant sameCreatedAt = Instant.parse("2026-08-10T12:00:00Z");
        this.forgeIt.postgresql()
                .create()
                .to(WORKFLOW_RUN.withEntity(this.workflowRunEntity(lowerRunId, sameCreatedAt)))
                .to(WORKFLOW_RUN.withEntity(this.workflowRunEntity(higherRunId, sameCreatedAt)))
                .build();

        this.forgeIt.mockMvc()
                .ping(LIST_WORKFLOW_RUNS)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$", hasSize(2)))
                .andExpectPath(jsonPath("$[0].id").value(higherRunId.toString()))
                .andExpectPath(jsonPath("$[1].id").value(lowerRunId.toString()))
                .andExpectPath(jsonPath("$[0].createdAt").value("2026-08-10T12:00:00Z"))
                .andExpectPath(jsonPath("$[1].createdAt").value("2026-08-10T12:00:00Z"))
                .andExpectPath(jsonPath("$[0].workflowName").value("Full Testing"))
                .andExpectPath(jsonPath("$[0].status").value("QUEUED"))
                .andExpectPath(jsonPath("$[0].nodeRuns").doesNotExist())
                .andExpectPath(jsonPath("$[0].input").doesNotExist())
                .assertAndCreate();

        final List<WorkflowRunEntity> persisted = this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll();
        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(WorkflowRunEntity::getStartedAt).containsOnlyNulls();
        assertThat(persisted).extracting(WorkflowRunEntity::getFinishedAt).containsOnlyNulls();
    }

    private WorkflowRunEntity createRun() {
        return this.createRun("Full Testing", 3);
    }

    private WorkflowRunEntity createRun(final String expectedWorkflowName, final int expectedNodeRunCount) {
        var builder = this.forgeIt.mockMvc()
                .ping(CREATE_WORKFLOW_RUN)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestCreateWorkflowRun.json")
                .expectStatus(HttpStatus.CREATED)
                .andExpectPath(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/workflow-runs/")))
                .andExpectPath(jsonPath("$.id").isNotEmpty())
                .andExpectPath(jsonPath("$.projectId").value(PROJECT_ALPHA_ID.toString()))
                .andExpectPath(jsonPath("$.sourceWorkflowId").value(WORKFLOW_ID.toString()))
                .andExpectPath(jsonPath("$.workflowName").value(expectedWorkflowName))
                .andExpectPath(jsonPath("$.input").value("Review the authentication service changes."))
                .andExpectPath(jsonPath("$.status").value("QUEUED"))
                .andExpectPath(jsonPath("$.startedAt").value(nullValue()))
                .andExpectPath(jsonPath("$.finishedAt").value(nullValue()))
                .andExpectPath(jsonPath("$.nodeRuns", hasSize(expectedNodeRunCount)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "sourceAgentId")).value(contains(AGENT_A_ID.toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "status")).value(contains("PENDING")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "output")).value(contains(nullValue())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "failure")).value(contains(nullValue())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "startedAt")).value(contains(nullValue())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "finishedAt")).value(contains(nullValue())));
        if (expectedNodeRunCount == 3 && "Full Testing".equals(expectedWorkflowName)) {
            builder = builder
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "sourceAgentId")).value(contains(AGENT_B_ID.toString())))
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentName")).value(contains("Agent A")))
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentInstructions")).value(contains("Do work for Agent A.")))
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentOutputSchema.type")).value(contains("object")))
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "dependsOnNodeRunIds")).value(contains(empty())))
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "dependsOnNodeRunIds[0]")).value(not(contains(NODE_A_ID.toString()))))
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "position.x")).value(contains(120.0)))
                    .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "position.y")).value(contains(100.0)));
        }
        builder.assertAndCreate();
        return this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll().stream()
                .max(Comparator.comparing(WorkflowRunEntity::getCreatedAt).thenComparing(WorkflowRunEntity::getId))
                .orElseThrow();
    }

    private void assertGetThreeNodeGraphSnapshot(final UUID runId) {
        final NodeRunEntity nodeA = this.nodeRun(runId, NODE_A_ID);
        final NodeRunEntity nodeB = this.nodeRun(runId, NODE_B_ID);
        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW_RUN)
                .withPathParameters(PathParams.create().add("runId", runId))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.id").value(runId.toString()))
                .andExpectPath(jsonPath("$.projectId").value(PROJECT_ALPHA_ID.toString()))
                .andExpectPath(jsonPath("$.sourceWorkflowId").value(WORKFLOW_ID.toString()))
                .andExpectPath(jsonPath("$.workflowName").value("Full Testing"))
                .andExpectPath(jsonPath("$.input").value("Review the authentication service changes."))
                .andExpectPath(jsonPath("$.status").value("QUEUED"))
                .andExpectPath(jsonPath("$.startedAt").value(nullValue()))
                .andExpectPath(jsonPath("$.finishedAt").value(nullValue()))
                .andExpectPath(jsonPath("$.nodeRuns", hasSize(3)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "id")).value(contains(nodeA.getId().toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "id")).value(contains(nodeB.getId().toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "sourceAgentId")).value(contains(AGENT_A_ID.toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "sourceAgentId")).value(contains(AGENT_B_ID.toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_C_ID, "sourceAgentId")).value(contains(AGENT_A_ID.toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentName")).value(contains("Agent A")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentInstructions")).value(contains("Do work for Agent A.")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "agentOutputSchema.type")).value(contains("object")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "status")).value(contains("PENDING")))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "dependsOnNodeRunIds")).value(contains(empty())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "dependsOnNodeRunIds[0]")).value(contains(nodeA.getId().toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_C_ID, "dependsOnNodeRunIds[0]")).value(contains(nodeB.getId().toString())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "dependsOnNodeRunIds[0]")).value(not(contains(NODE_A_ID.toString()))))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_C_ID, "dependsOnNodeRunIds[0]")).value(not(contains(NODE_B_ID.toString()))))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "position.x")).value(contains(120.0)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "position.y")).value(contains(100.0)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "position.x")).value(contains(420.0)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_B_ID, "position.y")).value(contains(40.0)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_C_ID, "position.x")).value(contains(720.0)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_C_ID, "position.y")).value(contains(120.0)))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "output")).value(contains(nullValue())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "failure")).value(contains(nullValue())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "startedAt")).value(contains(nullValue())))
                .andExpectPath(jsonPath(this.nodeRunPath(NODE_A_ID, "finishedAt")).value(contains(nullValue())))
                .assertAndCreate();
    }

    private void UPDATE_WORKFLOW(final String requestFixture) {
        this.forgeIt.mockMvc()
                .ping(ForgeAgentMockMvcEndpoint.UPDATE_WORKFLOW)
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

    private WorkflowRunEntity workflowRun(final UUID workflowRunId) {
        return this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll().stream()
                .filter(run -> workflowRunId.equals(run.getId()))
                .findFirst()
                .orElseThrow();
    }

    private NodeRunEntity nodeRun(final UUID workflowRunId, final UUID sourceNodeId) {
        return this.nodeRuns(workflowRunId).stream()
                .filter(nodeRun -> sourceNodeId.equals(nodeRun.getSourceNodeId()))
                .findFirst()
                .orElseThrow();
    }

    private String nodeRunPath(final UUID sourceNodeId, final String propertyPath) {
        return "$.nodeRuns[?(@.sourceNodeId == '%s')].%s".formatted(sourceNodeId, propertyPath);
    }

    private WorkflowRunEntity workflowRunEntity(final UUID runId, final Instant createdAt) {
        final WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setId(runId);
        entity.setProjectId(PROJECT_ALPHA_ID);
        entity.setSourceWorkflowId(WORKFLOW_ID);
        entity.setWorkflowName("Full Testing");
        entity.setInput("History only");
        entity.setStatus("QUEUED");
        entity.setCreatedAt(createdAt);
        return entity;
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
