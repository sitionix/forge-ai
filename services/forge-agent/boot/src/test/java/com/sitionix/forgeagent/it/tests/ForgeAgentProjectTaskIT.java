package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_PROJECT_TASK;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CREATE_PROJECT_TASK_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.GET_PROJECT_TASK;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_PROJECT_TASKS;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_PROJECT_TASKS_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT_TASK;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW_RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ExecutionFrameEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunPortEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeagent.domain.model.ProjectTask;
import com.sitionix.forgeagent.domain.port.ProjectTaskRepository;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.mockmvc.api.QueryParams;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ForgeAgentProjectTaskIT {

    private static final UUID REPOSITORY_A1_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_A2_ID = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID REPOSITORY_B1_ID = UUID.fromString("70000000-0000-4000-8000-000000000003");
    private static final UUID PROJECT_B_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private ProjectTaskRepository projectTaskRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void givenPortAwareWorkflow_whenCreateTask_thenRuntimeSnapshotAndRootRunArePersisted() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_PROJECT_TASK)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateProjectTask.json")
                .expectStatus(HttpStatus.CREATED)
                .andExpectPath(jsonPath("$.repositoryIds[0]").value(REPOSITORY_A2_ID.toString()))
                .andExpectPath(jsonPath("$.repositoryIds[1]").value(REPOSITORY_A1_ID.toString()))
                .assertAndCreate();

        final ProjectTaskEntity persistedTask = this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll().getFirst();
        assertThat(persistedTask.getRepositoryIds()).containsExactly(REPOSITORY_A2_ID, REPOSITORY_A1_ID);
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).singleElement().satisfies(run -> {
            assertThat(run.getSourceWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(run.getTaskId()).isNotNull();
            assertThat(run.getStatus()).isEqualTo("QUEUED");
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

        this.forgeIt.mockMvc()
                .ping(GET_PROJECT_TASK)
                .withPathParameters(PathParams.create().add("taskId", persistedTask.getId()))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.repositoryIds[0]").value(REPOSITORY_A2_ID.toString()))
                .andExpectPath(jsonPath("$.repositoryIds[1]").value(REPOSITORY_A1_ID.toString()))
                .assertAndCreate();
    }

    @Test
    void givenUnknownRepository_whenCreateTask_thenControlledErrorLeavesNoTaskOrRun() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_PROJECT_TASK_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateProjectTaskUnknownRepository.json")
                .expectStatus(HttpStatus.NOT_FOUND)
                .andExpectPath(jsonPath("$.code").value("PROJECT_REPOSITORY_NOT_FOUND"))
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenCrossProjectRepository_whenCreateTask_thenControlledErrorLeavesNoTaskOrRun() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_PROJECT_TASK_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateProjectTaskCrossProjectRepository.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .andExpectPath(jsonPath("$.code").value("PROJECT_REPOSITORY_PROJECT_MISMATCH"))
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void projectTaskRepositoryPersistsOrderedAssociationsAndDatabaseConstraints() {
        this.seedProjectAgentsAndWorkflow();
        final UUID taskId = UUID.fromString("50000000-0000-4000-8000-000000000020");
        final Instant createdAt = Instant.parse("2026-08-10T12:00:00Z");

        this.projectTaskRepository.save(new ProjectTask(
                taskId, PROJECT_ALPHA_ID, "Persistence contract", "Verify ordering.", WORKFLOW_ID,
                List.of(REPOSITORY_A2_ID, REPOSITORY_A1_ID), createdAt, createdAt));

        assertThat(this.projectTaskRepository.findById(taskId).orElseThrow().repositoryIds())
                .containsExactly(REPOSITORY_A2_ID, REPOSITORY_A1_ID);
        assertThat(this.projectTaskRepository.findPageByProjectId(PROJECT_ALPHA_ID, 0, 20).items())
                .extracting(ProjectTask::id).contains(taskId);
        assertThat(this.jdbcTemplate.queryForList(
                "SELECT repository_ordinal FROM project_task_repositories WHERE task_id = ? ORDER BY repository_ordinal",
                Integer.class, taskId)).containsExactly(0, 1);
        assertThatThrownBy(() -> this.jdbcTemplate.update(
                "INSERT INTO project_task_repositories(task_id, repository_id, repository_ordinal) VALUES (?, ?, ?)",
                taskId, REPOSITORY_A2_ID, 2)).isInstanceOf(DataIntegrityViolationException.class);

        this.projectTaskRepository.deleteById(taskId);
        assertThat(this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_task_repositories WHERE task_id = ?", Long.class, taskId)).isZero();
    }

    @Test
    void givenEmptyWorkflow_whenCreateTaskFails_thenNoTaskOrRunIsPersisted() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(CREATE_PROJECT_TASK_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateProjectTask.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .andExpectPath(jsonPath("$.code").value("WORKFLOW_ENTRY_NOT_FOUND"))
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenTaskWithMultipleRuns_whenGetTask_thenRunsAreNewestFirst() {
        this.seedProjectAgentsAndWorkflow();
        final UUID taskId = UUID.fromString("50000000-0000-4000-8000-000000000010");
        final UUID firstRunId = UUID.fromString("50000000-0000-4000-8000-000000000098");
        final UUID secondRunId = UUID.fromString("50000000-0000-4000-8000-000000000099");
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT_TASK.withEntity(this.projectTaskEntity(taskId, "Task with runs", Instant.parse("2026-08-10T12:00:00Z"))))
                .to(WORKFLOW_RUN.withEntity(this.workflowRunEntity(firstRunId, taskId, Instant.parse("2026-08-10T12:00:00Z"))))
                .to(WORKFLOW_RUN.withEntity(this.workflowRunEntity(secondRunId, taskId, Instant.parse("2099-08-10T12:01:00Z"))))
                .build();

        this.forgeIt.mockMvc()
                .ping(GET_PROJECT_TASK)
                .withPathParameters(PathParams.create().add("taskId", taskId))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.runs", hasSize(2)))
                .andExpectPath(jsonPath("$.runs[0].id").value(secondRunId.toString()))
                .andExpectPath(jsonPath("$.runs[1].id").value(firstRunId.toString()))
                .assertAndCreate();
    }

    @Test
    void givenSeededProjectTasks_whenListTasksWithPagination_thenSecondPageMetadataAndOrderingAreReturned() {
        this.seedProjectAgentsAndWorkflow();
        final UUID newestTaskId = UUID.fromString("50000000-0000-4000-8000-000000000005");
        final UUID tieHighTaskId = UUID.fromString("50000000-0000-4000-8000-000000000004");
        final UUID tieLowTaskId = UUID.fromString("50000000-0000-4000-8000-000000000003");
        final UUID olderTaskId = UUID.fromString("50000000-0000-4000-8000-000000000002");
        final UUID oldestTaskId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT_TASK.withEntity(this.projectTaskEntity(oldestTaskId, "Oldest task", Instant.parse("2026-08-14T10:00:00Z"))))
                .to(PROJECT_TASK.withEntity(this.projectTaskEntity(olderTaskId, "Older task", Instant.parse("2026-08-14T10:01:00Z"))))
                .to(PROJECT_TASK.withEntity(this.projectTaskEntity(tieLowTaskId, "Tie low id task", Instant.parse("2026-08-14T10:02:00Z"))))
                .to(PROJECT_TASK.withEntity(this.projectTaskEntity(tieHighTaskId, "Tie high id task", Instant.parse("2026-08-14T10:02:00Z"))))
                .to(PROJECT_TASK.withEntity(this.projectTaskEntity(newestTaskId, "Newest task", Instant.parse("2026-08-14T10:03:00Z"))))
                .build();

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_TASKS)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withQueryParameters(QueryParams.create().add("page", "0").add("size", "3"))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.items", hasSize(3)))
                .andExpectPath(jsonPath("$.page").value(0))
                .andExpectPath(jsonPath("$.size").value(3))
                .andExpectPath(jsonPath("$.totalItems").value(5))
                .andExpectPath(jsonPath("$.totalPages").value(2))
                .andExpectPath(jsonPath("$.items[0].id").value(newestTaskId.toString()))
                .andExpectPath(jsonPath("$.items[0].title").value("Newest task"))
                .andExpectPath(jsonPath("$.items[1].id").value(tieHighTaskId.toString()))
                .andExpectPath(jsonPath("$.items[1].title").value("Tie high id task"))
                .andExpectPath(jsonPath("$.items[2].id").value(tieLowTaskId.toString()))
                .andExpectPath(jsonPath("$.items[2].title").value("Tie low id task"))
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_TASKS)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withQueryParameters(QueryParams.create().add("page", "1").add("size", "2"))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.items", hasSize(2)))
                .andExpectPath(jsonPath("$.page").value(1))
                .andExpectPath(jsonPath("$.size").value(2))
                .andExpectPath(jsonPath("$.totalItems").value(5))
                .andExpectPath(jsonPath("$.totalPages").value(3))
                .andExpectPath(jsonPath("$.items[0].id").value(tieLowTaskId.toString()))
                .andExpectPath(jsonPath("$.items[1].id").value(olderTaskId.toString()))
                .assertAndCreate();
    }

    @Test
    void givenInvalidTaskPageRequest_whenListTasks_thenValidationErrorIsReturned() {
        this.seedProjectAgentsAndWorkflow();

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_TASKS_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withQueryParameters(QueryParams.create().add("page", "-1").add("size", "20"))
                .expectStatus(HttpStatus.BAD_REQUEST)
                .andExpectPath(jsonPath("$.code").value("INVALID_TASK_PAGE"))
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_TASKS_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withQueryParameters(QueryParams.create().add("page", "0").add("size", "0"))
                .expectStatus(HttpStatus.BAD_REQUEST)
                .andExpectPath(jsonPath("$.code").value("INVALID_TASK_PAGE_SIZE"))
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_TASKS_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withQueryParameters(QueryParams.create().add("page", "0").add("size", "101"))
                .expectStatus(HttpStatus.BAD_REQUEST)
                .andExpectPath(jsonPath("$.code").value("INVALID_TASK_PAGE_SIZE"))
                .assertAndCreate();
    }

    @Test
    void givenMissingProject_whenListTasks_thenProjectNotFoundIsReturned() {
        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_TASKS_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.NOT_FOUND)
                .andExpectPath(jsonPath("$.code").value("PROJECT_NOT_FOUND"))
                .assertAndCreate();
    }

    private void updateWorkflow() {
        this.forgeIt.mockMvc()
                .ping(UPDATE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .withRequest("requestUpdateWorkflowGraph.json")
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();
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

    private ProjectTaskEntity projectTaskEntity(final UUID taskId, final String title, final Instant createdAt) {
        final ProjectTaskEntity entity = new ProjectTaskEntity();
        entity.setId(taskId);
        entity.setProjectId(PROJECT_ALPHA_ID);
        entity.setTitle(title);
        entity.setInput("Input for " + title);
        entity.setWorkflowId(WORKFLOW_ID);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt);
        return entity;
    }

    private void seedProjectAgentsAndWorkflow() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(PROJECT.withEntity(this.projectBEntity()))
                .to(PROJECT_REPOSITORY.withEntity(this.projectRepositoryEntity(REPOSITORY_A1_ID, PROJECT_ALPHA_ID, "repo-a1")))
                .to(PROJECT_REPOSITORY.withEntity(this.projectRepositoryEntity(REPOSITORY_A2_ID, PROJECT_ALPHA_ID, "repo-a2")))
                .to(PROJECT_REPOSITORY.withEntity(this.projectRepositoryEntity(REPOSITORY_B1_ID, PROJECT_B_ID, "repo-b1")))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json"))
                .to(AGENT_DEFINITION.withJson("agent_c.json"))
                .to(WORKFLOW.withJson("workflow_alpha.json"))
                .build();
    }

    private ProjectRepositoryEntity projectRepositoryEntity(final UUID id, final UUID projectId, final String name) {
        final ProjectRepositoryEntity entity = new ProjectRepositoryEntity();
        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setRemoteUrl("https://example.com/forge/" + name + ".git");
        entity.setCreatedAt(Instant.parse("2026-08-10T10:00:00Z"));
        return entity;
    }

    private ProjectEntity projectBEntity() {
        final ProjectEntity entity = new ProjectEntity();
        entity.setId(PROJECT_B_ID);
        entity.setName("Project B");
        entity.setNormalizedName("project b");
        entity.setCreatedAt(Instant.parse("2026-08-10T09:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-08-10T09:00:00Z"));
        return entity;
    }
}
