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
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_WORKFLOW;
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

import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodePortEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
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
        assertThat(reviewerAgain.getPositionX()).isEqualTo(720.0);
        assertThat(reviewerAgain.getPositionY()).isEqualTo(120.0);
        assertThat(reviewerAgain.getWorkflowId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    void givenWorkflowWithPorts_whenPutGetUpdateAndDelete_thenPortsPersistByStableId() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestWorkflowWithPorts.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowWithPorts.json", "updatedAt")
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowWithPorts.json", "updatedAt")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(WorkflowNodePortEntity.class).getAll())
                .hasSize(4)
                .extracting(WorkflowNodePortEntity::getId, WorkflowNodePortEntity::getDirection, WorkflowNodePortEntity::getName, WorkflowNodePortEntity::getPortOrder)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("60000000-0000-4000-8000-000000000001"), "INPUT", "Review feedback", 0),
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("60000000-0000-4000-8000-000000000002"), "INPUT", "Context", 1),
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("70000000-0000-4000-8000-000000000001"), "OUTPUT", "Approved", 0),
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("70000000-0000-4000-8000-000000000002"), "OUTPUT", "Return", 1)
                );

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestWorkflowWithPortsUpdated.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowWithPortsUpdated.json", "updatedAt")
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowWithPortsUpdated.json", "updatedAt")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(WorkflowNodePortEntity.class).getAll())
                .hasSize(4)
                .extracting(WorkflowNodePortEntity::getId, WorkflowNodePortEntity::getName, WorkflowNodePortEntity::getDescription, WorkflowNodePortEntity::getPortOrder)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("60000000-0000-4000-8000-000000000001"), "Review feedback", "Updated feedback description.", 0),
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("60000000-0000-4000-8000-000000000003"), "Test result", "Latest automated test result.", 1),
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("70000000-0000-4000-8000-000000000001"), "Ready", "Continue when ready for testing.", 0),
                        org.assertj.core.groups.Tuple.tuple(UUID.fromString("70000000-0000-4000-8000-000000000003"), "Reject", "Reject when the implementation cannot proceed.", 1)
                );

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestWorkflowNoNodes.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
        assertThat(this.forgeIt.postgresql().get(WorkflowNodePortEntity.class).getAll()).isEmpty();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestWorkflowWithPorts.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(DELETE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();
        assertThat(this.forgeIt.postgresql().get(WorkflowNodePortEntity.class).getAll()).isEmpty();
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
        assertThat(nodeA.getPositionX()).isEqualTo(120.0);
        assertThat(nodeA.getPositionY()).isEqualTo(100.0);
    }

    @Test
    void givenInvalidWorkflowGraphs_whenUpdateWorkflow_thenControlledErrorsAreReturned() {
        this.seedTwoProjectsAgentsAndWorkflow();

        this.expectWorkflowError("requestUnknownNodeTarget.json", HttpStatus.BAD_REQUEST, "responseUnknownNodeTargetError.json");
        this.expectWorkflowError("requestCrossProjectNodeTarget.json", HttpStatus.CONFLICT, "responseCrossProjectNodeTargetError.json");
        this.expectWorkflowError("requestUnknownSourceOutputPort.json", HttpStatus.BAD_REQUEST, "responseUnknownSourceOutputPortError.json");
        this.expectWorkflowError("requestSelfNodeConnection.json", HttpStatus.BAD_REQUEST, "responseSelfNodeConnectionError.json");
        this.expectWorkflowError("requestDuplicateNodeIdWorkflow.json", HttpStatus.BAD_REQUEST, "responseDuplicateNodeIdError.json");
        this.expectWorkflowError("requestWorkflowDuplicatePortName.json", HttpStatus.BAD_REQUEST, "responseDuplicateNodePortNameError.json");
        this.expectWorkflowError("requestWorkflowPortOrderGap.json", HttpStatus.BAD_REQUEST, "responseInvalidNodePortOrderError.json");
        this.expectWorkflowError("requestWorkflowBlankPortField.json", HttpStatus.BAD_REQUEST, "responseInvalidNodePortError.json");
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

        this.expectWorkflowError("requestSelfNodeConnection.json", HttpStatus.BAD_REQUEST, "responseSelfNodeConnectionError.json");

        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseWorkflowGraph.json", "updatedAt")
                .assertAndCreate();
    }

    @Test
    void givenPortIdOwnedByAnotherWorkflow_whenUpdateWorkflow_thenConflictAndTopologiesRemainUnchanged() {
        this.seedProjectAgentsAndWorkflow();
        final UUID secondWorkflowId = UUID.fromString("30000000-0000-4000-8000-000000000002");
        this.forgeIt.postgresql()
                .create()
                .to(WORKFLOW.withEntity(this.workflowEntity(secondWorkflowId, PROJECT_ALPHA_ID, "Second Workflow")))
                .build();
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestWorkflowWithPorts.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", secondWorkflowId))
                .withRequest("requestCrossWorkflowPortId.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseWorkflowNodePortIdInUseError.json")
                .assertAndCreate();

        final UUID reusedPortId = UUID.fromString("60000000-0000-4000-8000-000000000001");
        final WorkflowNodePortEntity workflowAPort = this.forgeIt.postgresql()
                .get(WorkflowNodePortEntity.class)
                .getAll()
                .stream()
                .filter(port -> reusedPortId.equals(port.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(workflowAPort.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(workflowAPort.getNodeId()).isEqualTo(NODE_A_ID);
        assertThat(workflowAPort.getName()).isEqualTo("Review feedback");
        assertThat(workflowAPort.getDescription()).isEqualTo("Feedback produced by the previous review step.");
        assertThat(workflowAPort.getPortOrder()).isZero();
        assertThat(this.forgeIt.postgresql().get(WorkflowNodePortEntity.class).getAll())
                .filteredOn(port -> secondWorkflowId.equals(port.getWorkflowId()))
                .isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowNodeEntity.class).getAll())
                .filteredOn(node -> secondWorkflowId.equals(node.getWorkflowId()))
                .isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowEntity.class).getAll())
                .filteredOn(workflow -> secondWorkflowId.equals(workflow.getId()))
                .singleElement()
                .extracting(WorkflowEntity::getName)
                .isEqualTo("Second Workflow");
    }

    @Test
    void givenConnectionIdOwnedByAnotherWorkflow_whenUpdateWorkflow_thenConflictAndTopologiesRemainUnchanged() {
        this.seedProjectAgentsAndWorkflow();
        final UUID secondWorkflowId = UUID.fromString("30000000-0000-4000-8000-000000000002");
        this.forgeIt.postgresql()
                .create()
                .to(WORKFLOW.withEntity(this.workflowEntity(secondWorkflowId, PROJECT_ALPHA_ID, "Second Workflow")))
                .build();
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestWorkflowAToBConnection.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", secondWorkflowId))
                .withRequest("requestCrossWorkflowConnectionId.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseWorkflowConnectionIdInUseError.json")
                .assertAndCreate();

        final UUID reusedConnectionId = UUID.fromString("81000000-0000-4000-8000-000000000002");
        final WorkflowConnectionEntity workflowAConnection = this.forgeIt.postgresql()
                .get(WorkflowConnectionEntity.class)
                .getAll()
                .stream()
                .filter(connection -> reusedConnectionId.equals(connection.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(workflowAConnection.getSourceOutputPortId()).isEqualTo(UUID.fromString("71000000-0000-4000-8000-000000000001"));
        assertThat(workflowAConnection.getTargetInputPortId()).isEqualTo(UUID.fromString("61000000-0000-4000-8000-000000000002"));
        assertThat(this.forgeIt.postgresql().get(WorkflowNodePortEntity.class).getAll())
                .filteredOn(port -> secondWorkflowId.equals(port.getWorkflowId()))
                .isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowNodeEntity.class).getAll())
                .filteredOn(node -> secondWorkflowId.equals(node.getWorkflowId()))
                .isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowConnectionEntity.class).getAll())
                .hasSize(1)
                .extracting(WorkflowConnectionEntity::getId)
                .containsExactly(reusedConnectionId);
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
                            .withRequest("requestWorkflowBToAConnection.json")
                            .expectStatus(HttpStatus.OK)
                            .assertAndCreate();
                    return null;
                },
                () -> {
                    this.forgeIt.mockMvc()
                            .ping(UPDATE_WORKFLOW)
                            .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                            .withRequest("requestWorkflowAToBConnection.json")
                            .expectStatus(HttpStatus.OK)
                            .assertAndCreate();
                    return null;
                }
        );

        final List<WorkflowNodeEntity> nodes = this.forgeIt.postgresql().get(WorkflowNodeEntity.class).getAll();
        assertThat(nodes).hasSize(2);
        final List<WorkflowConnectionEntity> connections = this.forgeIt.postgresql().get(WorkflowConnectionEntity.class).getAll();
        assertThat(connections).hasSize(1);
        final boolean aToB = connections.stream().anyMatch(connection ->
                UUID.fromString("71000000-0000-4000-8000-000000000001").equals(connection.getSourceOutputPortId())
                        && UUID.fromString("61000000-0000-4000-8000-000000000002").equals(connection.getTargetInputPortId())
        );
        final boolean bToA = connections.stream().anyMatch(connection ->
                UUID.fromString("71000000-0000-4000-8000-000000000002").equals(connection.getSourceOutputPortId())
                        && UUID.fromString("61000000-0000-4000-8000-000000000001").equals(connection.getTargetInputPortId())
        );
        assertThat(aToB).isNotEqualTo(bToA);
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

    private WorkflowEntity workflowEntity(final UUID id, final UUID projectId, final String name) {
        final WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(id);
        workflow.setProjectId(projectId);
        workflow.setName(name);
        workflow.setNormalizedName(name.toLowerCase(java.util.Locale.ROOT));
        workflow.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        workflow.setUpdatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        return workflow;
    }
}
