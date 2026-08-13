package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_PROJECT_TASK;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_PROJECT_TASK_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.GET_PROJECT_TASK;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_PROJECT_TASKS;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT_TASK;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW_RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentProjectTaskIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenNonEmptyWorkflow_whenCreateTask_thenTaskTriggersInitialWorkflowRunAndAppearsInReadModels() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();

        final UUID taskId = this.createTaskAndAssertResponse();
        final ProjectTaskEntity task = this.task(taskId);
        final WorkflowRunEntity run = this.latestRunForTask(taskId);

        assertThat(task.getProjectId()).isEqualTo(PROJECT_ALPHA_ID);
        assertThat(task.getTitle()).isEqualTo("Check calculation");
        assertThat(task.getInput()).isEqualTo("Count the letters in Sitionix.");
        assertThat(task.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(run.getProjectId()).isEqualTo(PROJECT_ALPHA_ID);
        assertThat(run.getSourceWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(run.getTaskId()).isEqualTo(taskId);
        assertThat(run.getInput()).isEqualTo("Count the letters in Sitionix.");
        assertThat(run.getStatus()).isEqualTo("QUEUED");
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll())
                .hasSize(3)
                .allSatisfy(nodeRun -> {
                    assertThat(nodeRun.getWorkflowRunId()).isEqualTo(run.getId());
                    assertThat(nodeRun.getStatus()).isEqualTo("PENDING");
                    assertThat(nodeRun.getStartedAt()).isNull();
                    assertThat(nodeRun.getFinishedAt()).isNull();
                });

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_TASKS)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$", hasSize(1)))
                .andExpectPath(jsonPath("$[0].id").value(taskId.toString()))
                .andExpectPath(jsonPath("$[0].projectId").value(PROJECT_ALPHA_ID.toString()))
                .andExpectPath(jsonPath("$[0].title").value("Check calculation"))
                .andExpectPath(jsonPath("$[0].workflowId").value(WORKFLOW_ID.toString()))
                .andExpectPath(jsonPath("$[0].workflowName").value("Full Testing"))
                .andExpectPath(jsonPath("$[0].latestWorkflowRunId").value(run.getId().toString()))
                .andExpectPath(jsonPath("$[0].executionStatus").value("QUEUED"))
                .andExpectPath(jsonPath("$[0].input").doesNotExist())
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(GET_PROJECT_TASK)
                .withPathParameters(PathParams.create().add("taskId", taskId))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.id").value(taskId.toString()))
                .andExpectPath(jsonPath("$.input").value("Count the letters in Sitionix."))
                .andExpectPath(jsonPath("$.runs", hasSize(1)))
                .andExpectPath(jsonPath("$.runs[0].id").value(run.getId().toString()))
                .andExpectPath(jsonPath("$.runs[0].taskId").value(taskId.toString()))
                .andExpectPath(jsonPath("$.runs[0].status").value("QUEUED"))
                .assertAndCreate();
    }

    @Test
    void givenEmptyWorkflow_whenCreateTaskFails_thenNoTaskOrRunIsPersisted() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_PROJECT_TASK_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateProjectTask.json")
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("EMPTY_WORKFLOW"))
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenTaskWithMultipleRuns_whenGetTask_thenRunsAreNewestFirst() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();
        final UUID taskId = this.createTaskAndAssertResponse();
        final WorkflowRunEntity firstRun = this.latestRunForTask(taskId);
        final UUID secondRunId = UUID.fromString("50000000-0000-4000-8000-000000000099");
        this.forgeIt.postgresql()
                .create()
                .to(WORKFLOW_RUN.withEntity(this.workflowRunEntity(secondRunId, taskId, Instant.parse("2099-08-10T12:01:00Z"))))
                .build();

        this.forgeIt.mockMvc()
                .ping(GET_PROJECT_TASK)
                .withPathParameters(PathParams.create().add("taskId", taskId))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.runs", hasSize(2)))
                .andExpectPath(jsonPath("$.runs[0].id").value(secondRunId.toString()))
                .andExpectPath(jsonPath("$.runs[1].id").value(firstRun.getId().toString()))
                .assertAndCreate();
    }

    private UUID createTaskAndAssertResponse() {
        this.forgeIt.mockMvc()
                .ping(CREATE_PROJECT_TASK)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateProjectTask.json")
                .expectStatus(HttpStatus.CREATED)
                .andExpectPath(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/tasks/")))
                .andExpectPath(jsonPath("$.id").isNotEmpty())
                .andExpectPath(jsonPath("$.projectId").value(PROJECT_ALPHA_ID.toString()))
                .andExpectPath(jsonPath("$.title").value("Check calculation"))
                .andExpectPath(jsonPath("$.input").value("Count the letters in Sitionix."))
                .andExpectPath(jsonPath("$.workflowId").value(WORKFLOW_ID.toString()))
                .andExpectPath(jsonPath("$.runs", hasSize(1)))
                .andExpectPath(jsonPath("$.runs[0].taskId").isNotEmpty())
                .andExpectPath(jsonPath("$.runs[0].workflowName").value("Full Testing"))
                .andExpectPath(jsonPath("$.runs[0].status").value("QUEUED"))
                .andExpectPath(jsonPath("$.runs[0].startedAt").value(nullValue()))
                .andExpectPath(jsonPath("$.runs[0].finishedAt").value(nullValue()))
                .assertAndCreate();
        return this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll().stream()
                .max(Comparator.comparing(ProjectTaskEntity::getCreatedAt).thenComparing(ProjectTaskEntity::getId))
                .map(ProjectTaskEntity::getId)
                .orElseThrow();
    }

    private void updateWorkflow() {
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
    }

    private ProjectTaskEntity task(final UUID taskId) {
        return this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll().stream()
                .filter(task -> taskId.equals(task.getId()))
                .findFirst()
                .orElseThrow();
    }

    private WorkflowRunEntity latestRunForTask(final UUID taskId) {
        return this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll().stream()
                .filter(run -> taskId.equals(run.getTaskId()))
                .max(Comparator.comparing(WorkflowRunEntity::getCreatedAt).thenComparing(WorkflowRunEntity::getId))
                .orElseThrow();
    }

    private WorkflowRunEntity workflowRunEntity(final UUID runId, final UUID taskId, final Instant createdAt) {
        final WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setId(runId);
        entity.setProjectId(PROJECT_ALPHA_ID);
        entity.setSourceWorkflowId(WORKFLOW_ID);
        entity.setTaskId(taskId);
        entity.setWorkflowName("Full Testing");
        entity.setInput("Second attempt");
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
}
