package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.NODE_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_BETA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.UNKNOWN_WORKFLOW_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_WORKFLOW_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.GET_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.GET_WORKFLOW_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_PROJECT_WORKFLOWS;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_AGENT;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_WORKFLOW_ERROR;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentWorkflowIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenWorkflowRequest_whenCreateAndListWorkflow_thenWorkflowIsPersisted() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(CREATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateWorkflow.json")
                .expectStatus(HttpStatus.CREATED)
                .expectResponse("responseCreateWorkflow.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_WORKFLOWS)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseListWorkflows.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        this.forgeIt.postgresql()
                .get(WorkflowEntity.class)
                .singleElement()
                .andExpected(entity -> "Full Testing".equals(entity.getName()))
                .andExpected(entity -> PROJECT_ALPHA_ID.equals(entity.getProjectId()))
                .assertEntity();
    }

    @Test
    void givenDuplicateWorkflowName_whenCreateWorkflow_thenConflictIsReturned() {
        this.seedProjectAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateWorkflowLowercase.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseDuplicateWorkflowError.json")
                .assertAndCreate();
    }

    @Test
    void givenWorkflowGraph_whenPutAndGetWorkflow_thenGraphRoundTripsAndNodesPersist() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowGraph.json", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowGraph.json", "updatedAt")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(WorkflowNodeEntity.class).getAll())
                .hasSize(3)
                .extracting(WorkflowNodeEntity::getTargetId)
                .containsExactlyInAnyOrder(AGENT_A_ID, AGENT_B_ID, AGENT_A_ID);
        final WorkflowNodeEntity reviewerAgain = this.forgeIt.postgresql()
                .get(WorkflowNodeEntity.class)
                .getAll()
                .stream()
                .filter(entity -> NODE_C_ID.equals(entity.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(reviewerAgain.getTargetId()).isEqualTo(AGENT_A_ID);
        assertThat(Arrays.asList(reviewerAgain.getDependsOnNodeIds())).containsExactly(NODE_B_ID);
        assertThat(reviewerAgain.getPositionX()).isEqualTo(720.0);
        assertThat(reviewerAgain.getPositionY()).isEqualTo(120.0);
        assertThat(reviewerAgain.getWorkflowId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    void givenWorkflowGraph_whenMoveNodePositions_thenPositionsPersistExactly() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestMoveWorkflowNodes.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseMovedWorkflowGraph.json", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseMovedWorkflowGraph.json", "updatedAt")
                .assertAndCreate();
    }

    @Test
    void givenSameNodeIdInDifferentWorkflows_whenGraphsAreSaved_thenWorkflowOwnershipScopesPersistenceIdentity() {
        this.seedProjectAgentsAndWorkflow();
        final UUID secondWorkflowId = UUID.fromString("30000000-0000-4000-8000-000000000002");
        final WorkflowEntity secondWorkflow = new WorkflowEntity();
        secondWorkflow.setId(secondWorkflowId);
        secondWorkflow.setProjectId(PROJECT_ALPHA_ID);
        secondWorkflow.setName("Second Workflow");
        secondWorkflow.setNormalizedName("second workflow");
        secondWorkflow.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        secondWorkflow.setUpdatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        this.forgeIt.postgresql().create().to(WORKFLOW.withEntity(secondWorkflow)).build();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", secondWorkflowId))
                .withRequest("requestSecondWorkflowSameNodeId.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseSecondWorkflowSameNodeId.json", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowGraph.json", "updatedAt")
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", secondWorkflowId))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseSecondWorkflowSameNodeId.json", "updatedAt")
                .assertAndCreate();

        final List<WorkflowNodeEntity> sameIdRows = this.forgeIt.postgresql()
                .get(WorkflowNodeEntity.class)
                .getAll()
                .stream()
                .filter(entity -> NODE_A_ID.equals(entity.getId()))
                .toList();
        assertThat(sameIdRows)
                .hasSize(2)
                .extracting(WorkflowNodeEntity::getWorkflowId)
                .containsExactlyInAnyOrder(WORKFLOW_ID, secondWorkflowId);
        assertThat(sameIdRows.stream()
                .filter(entity -> WORKFLOW_ID.equals(entity.getWorkflowId()))
                .findFirst()
                .orElseThrow()
                .getPositionX()).isEqualTo(120.0);
        assertThat(sameIdRows.stream()
                .filter(entity -> secondWorkflowId.equals(entity.getWorkflowId()))
                .findFirst()
                .orElseThrow()
                .getPositionX()).isEqualTo(900.0);
    }

    @Test
    void givenWorkflowNodeTargetsAgent_whenAgentIsUpdated_thenWorkflowTopologyRemainsUnchanged() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(UPDATE_AGENT)
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .withRequest("requestUpdateAgent.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowGraph.json", "updatedAt")
                .assertAndCreate();

        final WorkflowNodeEntity nodeA = this.forgeIt.postgresql()
                .get(WorkflowNodeEntity.class)
                .getAll()
                .stream()
                .filter(entity -> WORKFLOW_ID.equals(entity.getWorkflowId()) && NODE_A_ID.equals(entity.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(nodeA.getTargetId()).isEqualTo(AGENT_A_ID);
        assertThat(nodeA.getDependsOnNodeIds()).isEmpty();
        assertThat(nodeA.getPositionX()).isEqualTo(120.0);
        assertThat(nodeA.getPositionY()).isEqualTo(100.0);
    }

    @Test
    void givenInvalidWorkflowGraphs_whenUpdateWorkflow_thenControlledErrorsAreReturned() {
        this.seedTwoProjectsAgentsAndWorkflow();

        this.expectWorkflowError("requestUnknownNodeTarget.json", HttpStatus.BAD_REQUEST, "responseUnknownNodeTargetError.json");
        this.expectWorkflowError("requestCrossProjectNodeTarget.json", HttpStatus.CONFLICT, "responseCrossProjectNodeTargetError.json");
        this.expectWorkflowError("requestUnknownNodeDependency.json", HttpStatus.BAD_REQUEST, "responseUnknownNodeDependencyError.json");
        this.expectWorkflowError("requestSelfNodeDependency.json", HttpStatus.BAD_REQUEST, "responseSelfNodeDependencyError.json");
        this.expectWorkflowError("requestDuplicateNodeIdWorkflow.json", HttpStatus.BAD_REQUEST, "responseDuplicateNodeIdError.json");
        this.expectWorkflowError("requestDirectCycleWorkflow.json", HttpStatus.CONFLICT, "responseWorkflowCycleError.json");
        this.expectWorkflowError("requestIndirectCycleWorkflow.json", HttpStatus.CONFLICT, "responseWorkflowCycleError.json");
    }

    @Test
    void givenValidWorkflow_whenInvalidUpdateFails_thenPreviousGraphRemainsUnchanged() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        this.expectWorkflowError("requestDirectCycleWorkflow.json", HttpStatus.CONFLICT, "responseWorkflowCycleError.json");

        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowGraph.json", "updatedAt")
                .assertAndCreate();
    }

    @Test
    void givenMissingWorkflow_whenGetWorkflow_thenNotFoundIsReturned() {
        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", UNKNOWN_WORKFLOW_ID))
                .expectStatus(HttpStatus.NOT_FOUND)
                .expectResponse("responseWorkflowNotFoundError.json")
                .assertAndCreate();
    }

    @Test
    void givenConcurrentInverseWorkflowUpdates_whenSameWorkflowLocked_thenCombinedCycleCannotPersist() throws Exception {
        this.seedProjectAgentsAndWorkflow();

        this.runConcurrently(
                () -> {
                    this.forgeIt.mockMvc()
                            .ping(UPDATE_WORKFLOW)
                            .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                            .withRequest("requestWorkflowADependsOnB.json")
                            .expectStatus(HttpStatus.OK)
                            .assertAndCreate();
                    return null;
                },
                () -> {
                    this.forgeIt.mockMvc()
                            .ping(UPDATE_WORKFLOW)
                            .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                            .withRequest("requestWorkflowBDependsOnA.json")
                            .expectStatus(HttpStatus.OK)
                            .assertAndCreate();
                    return null;
                }
        );

        final List<WorkflowNodeEntity> nodes = this.forgeIt.postgresql().get(WorkflowNodeEntity.class).getAll();
        assertThat(nodes).hasSize(2);
        final WorkflowNodeEntity nodeA = nodes.stream()
                .filter(entity -> NODE_A_ID.equals(entity.getId()))
                .findFirst()
                .orElseThrow();
        final WorkflowNodeEntity nodeB = nodes.stream()
                .filter(entity -> NODE_B_ID.equals(entity.getId()))
                .findFirst()
                .orElseThrow();
        final boolean aDependsOnB = Arrays.asList(nodeA.getDependsOnNodeIds()).contains(NODE_B_ID);
        final boolean bDependsOnA = Arrays.asList(nodeB.getDependsOnNodeIds()).contains(NODE_A_ID);
        assertThat(aDependsOnB && bDependsOnA).isFalse();
    }

    @Test
    void givenDifferentWorkflows_whenUpdatedConcurrently_thenBothWorkflowUpdatesSucceed() throws Exception {
        this.seedTwoProjectsAgentsAndWorkflow();
        final WorkflowEntity secondWorkflow = new WorkflowEntity();
        secondWorkflow.setId(UUID.fromString("30000000-0000-4000-8000-000000000002"));
        secondWorkflow.setProjectId(PROJECT_BETA_ID);
        secondWorkflow.setName("Beta Workflow");
        secondWorkflow.setNormalizedName("beta workflow");
        secondWorkflow.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        secondWorkflow.setUpdatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        this.forgeIt.postgresql().create().to(WORKFLOW.withEntity(secondWorkflow)).build();

        this.runConcurrently(
                () -> {
                    this.forgeIt.mockMvc()
                            .ping(UPDATE_WORKFLOW)
                            .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                            .withRequest("requestMoveWorkflowNodes.json")
                            .expectStatus(HttpStatus.OK)
                            .assertAndCreate();
                    return null;
                },
                () -> {
                    this.forgeIt.mockMvc()
                            .ping(UPDATE_WORKFLOW)
                            .withPathParameters(PathParams.create().add("workflowId", secondWorkflow.getId()))
                            .withRequest("requestBetaWorkflowSingleNode.json")
                            .expectStatus(HttpStatus.OK)
                            .assertAndCreate();
                    return null;
                }
        );

        assertThat(this.forgeIt.postgresql().get(WorkflowEntity.class).getAll())
                .extracting(WorkflowEntity::getId)
                .containsExactlyInAnyOrder(WORKFLOW_ID, secondWorkflow.getId());
    }

    private void runConcurrently(final Callable<Void> first, final Callable<Void> second) throws Exception {
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<Void> firstResult = executor.submit(waitAndCall(ready, start, first));
            final Future<Void> secondResult = executor.submit(waitAndCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<Void> waitAndCall(final CountDownLatch ready,
                                              final CountDownLatch start,
                                              final Callable<Void> task) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return task.call();
        };
    }

    private void expectWorkflowError(final String requestFixture, final HttpStatus status, final String responseFixture) {
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest(requestFixture)
                .expectStatus(status)
                .expectResponse(responseFixture)
                .assertAndCreate();
    }

    private void seedProject() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .build();
    }

    private void seedProjectAndWorkflow() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(WORKFLOW.withJson("workflow_alpha.json"))
                .build();
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
