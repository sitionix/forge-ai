package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.UNKNOWN_AGENT_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.UNKNOWN_WORKFLOW_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_AGENT;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_AGENT_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_PROJECT;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_PROJECT_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_PROJECT_TASK;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_PROJECT_TASK_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_WORKFLOW_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.UPDATE_WORKFLOW;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDefinitionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ExecutionFrameEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataAgentDefinitionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataExecutionFrameRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataNodeRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectTaskRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentDeleteIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Autowired
    private SpringDataProjectTaskRepository taskRepository;

    @Autowired
    private SpringDataWorkflowRunRepository workflowRunRepository;

    @Autowired
    private SpringDataNodeRunRepository nodeRunRepository;

    @Autowired
    private SpringDataExecutionFrameRepository executionFrameRepository;

    @Autowired
    private SpringDataAgentDefinitionRepository agentRepository;

    @Autowired
    private SpringDataWorkflowNodeRepository workflowNodeRepository;

    @Autowired
    private SpringDataWorkflowRepository workflowRepository;

    @Autowired
    private SpringDataProjectRepository projectRepository;

    @Test
    void givenMissingResources_whenDelete_thenResourceSpecificNotFoundIsReturned() {
        this.forgeIt.mockMvc().ping(DELETE_PROJECT_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.NOT_FOUND)
                .andExpectPath(jsonPath("$.code").value("PROJECT_NOT_FOUND"))
                .assertAndCreate();

        this.forgeIt.mockMvc().ping(DELETE_AGENT_ERROR)
                .withPathParameters(PathParams.create().add("agentId", UNKNOWN_AGENT_ID))
                .expectStatus(HttpStatus.NOT_FOUND)
                .andExpectPath(jsonPath("$.code").value("AGENT_NOT_FOUND"))
                .assertAndCreate();

        this.forgeIt.mockMvc().ping(DELETE_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", UNKNOWN_WORKFLOW_ID))
                .expectStatus(HttpStatus.NOT_FOUND)
                .andExpectPath(jsonPath("$.code").value("WORKFLOW_NOT_FOUND"))
                .assertAndCreate();

        this.forgeIt.mockMvc().ping(DELETE_PROJECT_TASK_ERROR)
                .withPathParameters(PathParams.create().add("taskId", UUID.fromString("50000000-0000-4000-8000-000000009999")))
                .expectStatus(HttpStatus.NOT_FOUND)
                .andExpectPath(jsonPath("$.code").value("PROJECT_TASK_NOT_FOUND"))
                .assertAndCreate();
    }

    @Test
    void givenTerminalProjectAggregate_whenDeleteProject_thenOwnedRowsAreRemoved() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();
        final UUID taskId = this.saveTask();
        final UUID runId = this.saveWorkflowRun("SUCCEEDED", taskId);
        this.nodeRunRepository.save(this.nodeRun(UUID.randomUUID(), runId, AGENT_A_ID, "SUCCEEDED"));

        this.forgeIt.mockMvc().ping(DELETE_PROJECT)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(AgentDefinitionEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowNodeEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(ExecutionFrameEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenActiveProjectExecution_whenDeleteProject_thenConflictAndRowsRemain() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();
        final UUID taskId = this.saveTask();
        this.saveWorkflowRun("RUNNING", taskId);

        this.forgeIt.mockMvc().ping(DELETE_PROJECT_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("PROJECT_HAS_ACTIVE_EXECUTIONS"))
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectEntity.class).getAll()).hasSize(1);
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).hasSize(1);
    }

    @Test
    void givenUnusedAgent_whenDeleteAgent_thenAgentIsRemoved() {
        this.seedProjectAndAgent();

        this.forgeIt.mockMvc().ping(DELETE_AGENT)
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();

        assertThat(this.agentRepository.findById(AGENT_A_ID)).isEmpty();
    }

    @Test
    void givenAgentUsedByWorkflowOrActiveNodeRun_whenDeleteAgent_thenConflict() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();

        this.forgeIt.mockMvc().ping(DELETE_AGENT_ERROR)
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("AGENT_IN_USE"))
                .assertAndCreate();

        this.cleanDatabase();
        this.seedProjectAndAgent();
        final UUID runId = this.saveWorkflowRun("RUNNING", null);
        this.nodeRunRepository.save(this.nodeRun(UUID.randomUUID(), runId, AGENT_A_ID, "RUNNING"));

        this.forgeIt.mockMvc().ping(DELETE_AGENT_ERROR)
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("AGENT_IN_USE"))
                .assertAndCreate();
    }

    @Test
    void givenTerminalHistoricalNodeRun_whenDeleteAgent_thenAgentIsRemovedAndHistoryRemains() {
        this.seedProjectAndAgent();
        final UUID runId = this.saveWorkflowRun("SUCCEEDED", null);
        this.nodeRunRepository.save(this.nodeRun(UUID.randomUUID(), runId, AGENT_A_ID, "SUCCEEDED"));

        this.forgeIt.mockMvc().ping(DELETE_AGENT)
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();

        assertThat(this.agentRepository.findById(AGENT_A_ID)).isEmpty();
        assertThat(this.nodeRunRepository.findAll()).hasSize(1);
    }

    @Test
    void givenWorkflowDeleteRules_whenDeleteWorkflow_thenExpectedBehaviorApplies() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();

        this.forgeIt.mockMvc().ping(DELETE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();
        assertThat(this.forgeIt.postgresql().get(WorkflowEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowNodeEntity.class).getAll()).isEmpty();

        this.cleanDatabase();
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();
        this.saveTask();
        this.forgeIt.mockMvc().ping(DELETE_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("WORKFLOW_IN_USE"))
                .assertAndCreate();

        this.cleanDatabase();
        this.seedProjectAgentsAndWorkflow();
        this.saveWorkflowRun("RUNNING", null);
        this.forgeIt.mockMvc().ping(DELETE_WORKFLOW_ERROR)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("WORKFLOW_IN_USE"))
                .assertAndCreate();

        this.cleanDatabase();
        this.seedProjectAgentsAndWorkflow();
        this.saveWorkflowRun("SUCCEEDED", null);
        this.forgeIt.mockMvc().ping(DELETE_WORKFLOW)
                .withPathParameters(PathParams.create().add("workflowId", WORKFLOW_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();
        assertThat(this.workflowRunRepository.findAll()).hasSize(1);
    }

    @Test
    void givenTaskDeleteRules_whenDeleteTask_thenExpectedBehaviorApplies() {
        this.seedProjectAgentsAndWorkflow();
        this.updateWorkflow();
        final UUID taskId = this.saveTask();
        final UUID runId = this.saveWorkflowRun("RUNNING", taskId);
        this.nodeRunRepository.save(this.nodeRun(UUID.randomUUID(), runId, AGENT_A_ID, "RUNNING"));

        this.forgeIt.mockMvc().ping(DELETE_PROJECT_TASK_ERROR)
                .withPathParameters(PathParams.create().add("taskId", taskId))
                .expectStatus(HttpStatus.CONFLICT)
                .andExpectPath(jsonPath("$.code").value("PROJECT_TASK_HAS_ACTIVE_EXECUTIONS"))
                .assertAndCreate();

        this.workflowRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus("SUCCEEDED");
            run.setStartedAt(run.getCreatedAt());
            run.setFinishedAt(run.getCreatedAt().plusSeconds(10));
            this.workflowRunRepository.save(run);
        });
        for (final NodeRunEntity nodeRun : this.nodeRunRepository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(runId)) {
            nodeRun.setStatus("SUCCEEDED");
            nodeRun.setStartedAt(nodeRun.getCreatedAt());
            nodeRun.setFinishedAt(nodeRun.getCreatedAt().plusSeconds(10));
            this.nodeRunRepository.save(nodeRun);
        }
        this.forgeIt.mockMvc().ping(DELETE_PROJECT_TASK)
                .withPathParameters(PathParams.create().add("taskId", taskId))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();

        assertThat(this.taskRepository.findById(taskId)).isEmpty();
        assertThat(this.workflowRunRepository.findAll()).isEmpty();
        assertThat(this.nodeRunRepository.findAll()).isEmpty();
    }

    private void seedProjectAndAgent() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .build();
    }

    private void cleanDatabase() {
        this.nodeRunRepository.deleteAll();
        this.workflowRunRepository.deleteAll();
        this.taskRepository.deleteAll();
        this.workflowNodeRepository.deleteAll();
        this.workflowRepository.deleteAll();
        this.agentRepository.deleteAll();
        this.projectRepository.deleteAll();
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

    private UUID saveTask() {
        final ProjectTaskEntity entity = new ProjectTaskEntity();
        entity.setId(UUID.randomUUID());
        entity.setProjectId(PROJECT_ALPHA_ID);
        entity.setTitle("Historical task");
        entity.setInput("Run workflow.");
        entity.setWorkflowId(WORKFLOW_ID);
        entity.setCreatedAt(Instant.parse("2026-08-14T09:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-08-14T09:00:00Z"));
        return this.taskRepository.save(entity).getId();
    }

    private UUID saveWorkflowRun(final String status, final UUID taskId) {
        final WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setId(UUID.randomUUID());
        entity.setProjectId(PROJECT_ALPHA_ID);
        entity.setSourceWorkflowId(WORKFLOW_ID);
        entity.setTaskId(taskId);
        entity.setWorkflowName("Full Testing");
        entity.setInput("Run workflow.");
        entity.setStatus(status);
        entity.setCreatedAt(Instant.parse("2026-08-14T10:00:00Z"));
        if (!"QUEUED".equals(status)) {
            entity.setStartedAt(entity.getCreatedAt());
        }
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            entity.setFinishedAt(entity.getCreatedAt().plusSeconds(10));
        }
        return this.workflowRunRepository.save(entity).getId();
    }

    private NodeRunEntity nodeRun(final UUID nodeRunId, final UUID workflowRunId, final UUID agentId, final String status) {
        final ExecutionFrameEntity frame = new ExecutionFrameEntity();
        frame.setId(UUID.randomUUID());
        frame.setWorkflowRunId(workflowRunId);
        frame.setCreatedAt(Instant.parse("2026-08-14T10:00:30Z"));
        this.executionFrameRepository.save(frame);

        final NodeRunEntity entity = new NodeRunEntity();
        entity.setId(nodeRunId);
        entity.setWorkflowRunId(workflowRunId);
        entity.setSourceNodeId(UUID.randomUUID());
        entity.setSourceAgentId(agentId);
        entity.setAgentName("Agent A");
        entity.setAgentInstructions("Do work.");
        entity.setAgentOutputSchema("{\"type\":\"object\"}");
        entity.setInputMode("DEPENDENCIES_ONLY");
        entity.setContextMode("FRESH_EACH_NODE_RUN");
        entity.setExecutionFrameId(frame.getId());
        entity.setPositionX(0.0);
        entity.setPositionY(0.0);
        entity.setStatus(status);
        entity.setCreatedAt(Instant.parse("2026-08-14T10:01:00Z"));
        if (!"PENDING".equals(status)) {
            entity.setStartedAt(entity.getCreatedAt());
        }
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "BLOCKED".equals(status) || "CANCELLED".equals(status)) {
            entity.setFinishedAt(entity.getCreatedAt().plusSeconds(10));
        }
        return entity;
    }
}
