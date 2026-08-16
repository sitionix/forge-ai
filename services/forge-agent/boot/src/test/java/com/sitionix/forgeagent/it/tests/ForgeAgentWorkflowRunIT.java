package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_WORKFLOW_RUN;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_WORKFLOW_RUN_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.GET_WORKFLOW_RUN;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_WORKFLOW_RUNS;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW_RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ExecutionFrameEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunPortEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentWorkflowRunIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenExecutableWorkflow_whenCreateRun_thenRuntimeSnapshotAndRootRunArePersisted() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_WORKFLOW_RUN)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestCreateWorkflowRun.json")
                .expectStatus(HttpStatus.CREATED)
                .andExpectPath(jsonPath("$.nodeRuns", hasSize(1)))
                .andExpectPath(jsonPath("$.connectionResolutions", hasSize(0)))
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).singleElement().satisfies(run -> {
            assertThat(run.getSourceWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(run.getStatus()).isEqualTo("QUEUED");
            assertThat(run.getTaskId()).isNull();
        });
        assertThat(this.forgeIt.postgresql().get(ExecutionFrameEntity.class).getAll()).hasSize(1);
        assertThat(this.forgeIt.postgresql().get(WorkflowRunNodeEntity.class).getAll()).hasSize(3);
        assertThat(this.forgeIt.postgresql().get(WorkflowRunPortEntity.class).getAll()).hasSize(6);
        assertThat(this.forgeIt.postgresql().get(WorkflowRunConnectionEntity.class).getAll()).hasSize(2);
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).singleElement().satisfies(nodeRun -> {
            assertThat(nodeRun.getSourceNodeId()).isEqualTo(UUID.fromString("40000000-0000-4000-8000-000000000001"));
            assertThat(nodeRun.getStatus()).isEqualTo("PENDING");
            assertThat(nodeRun.getExecutionFrameId()).isNotNull();
            assertThat(nodeRun.getActivationFrameId()).isNull();
            assertThat(nodeRun.getEnteredViaInputPortId()).isEqualTo(UUID.fromString("61000000-0000-4000-8000-000000000001"));
        });
    }

    @Test
    void givenMultipleHistoricalRuns_whenListHistory_thenNewestFirstWithDeterministicTieBreaker() {
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

    @Test
    void givenHistoricalRun_whenGetWorkflowRun_thenRunIsReadable() {
        this.seedProjectAgentsAndWorkflow();
        final UUID runId = UUID.fromString("50000000-0000-4000-8000-000000000003");
        this.forgeIt.postgresql()
                .create()
                .to(WORKFLOW_RUN.withEntity(this.workflowRunEntity(runId, Instant.parse("2026-08-10T12:00:00Z"))))
                .build();

        this.forgeIt.mockMvc()
                .ping(GET_WORKFLOW_RUN)
                .withPathParameters(PathParams.create().add("runId", runId))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.id").value(runId.toString()))
                .andExpectPath(jsonPath("$.projectId").value(PROJECT_ALPHA_ID.toString()))
                .andExpectPath(jsonPath("$.sourceWorkflowId").value(WORKFLOW_ID.toString()))
                .andExpectPath(jsonPath("$.taskId").value(nullValue()))
                .andExpectPath(jsonPath("$.workflowName").value("Full Testing"))
                .andExpectPath(jsonPath("$.input").value("History only"))
                .andExpectPath(jsonPath("$.status").value("QUEUED"))
                .andExpectPath(jsonPath("$.startedAt").value(nullValue()))
                .andExpectPath(jsonPath("$.finishedAt").value(nullValue()))
                .andExpectPath(jsonPath("$.nodeRuns", hasSize(0)))
                .assertAndCreate();
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

    private void updateWorkflow() {
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
    }
}
