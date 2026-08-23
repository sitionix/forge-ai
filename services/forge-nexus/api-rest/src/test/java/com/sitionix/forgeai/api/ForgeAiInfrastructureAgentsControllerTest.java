package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.api.agentproxy.AgentDefinitionListResponse;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionResponse;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeProviderResponse;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRunResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRunSummaryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRepositoryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRepositoryGitStateResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskPageResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskSummaryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRequest;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowResponse;
import com.sitionix.forgeai.api.agentproxy.CreateAgentWorkflowRunRequest;
import com.sitionix.forgeai.api.agentproxy.CreateAgentProjectTaskRequest;
import com.sitionix.forgeai.api.agentproxy.ImportAgentProjectRepositoryRequest;
import com.sitionix.forgeai.api.agentproxy.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProvider;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProviderStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.usecase.CreateAgentDefinition;
import com.sitionix.forgeai.domain.usecase.CreateAgentProject;
import com.sitionix.forgeai.domain.usecase.CreateAgentProjectTask;
import com.sitionix.forgeai.domain.usecase.CreateAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.CreateAgentWorkflowRun;
import com.sitionix.forgeai.domain.usecase.CloneAgentProjectRepository;
import com.sitionix.forgeai.domain.usecase.DeleteAgentDefinition;
import com.sitionix.forgeai.domain.usecase.DeleteAgentProject;
import com.sitionix.forgeai.domain.usecase.DeleteAgentProjectTask;
import com.sitionix.forgeai.domain.usecase.DeleteAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.GetAgentDefinition;
import com.sitionix.forgeai.domain.usecase.GetAgentRuntime;
import com.sitionix.forgeai.domain.usecase.GetAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.GetAgentWorkflowRun;
import com.sitionix.forgeai.domain.usecase.GetAgentProjectTask;
import com.sitionix.forgeai.domain.usecase.ImportAgentProjectRepository;
import com.sitionix.forgeai.domain.usecase.ListAgentProjectRepositories;
import com.sitionix.forgeai.domain.usecase.ListAgentProjects;
import com.sitionix.forgeai.domain.usecase.ListAgentProjectTasks;
import com.sitionix.forgeai.domain.usecase.ListAgentWorkflowRuns;
import com.sitionix.forgeai.domain.usecase.ListAgentWorkflows;
import com.sitionix.forgeai.domain.usecase.ListProjectAgentDefinitions;
import com.sitionix.forgeai.domain.usecase.PullAgentProjectRepository;
import com.sitionix.forgeai.domain.usecase.RefreshAgentProjectRepository;
import com.sitionix.forgeai.domain.usecase.UpdateAgentDefinition;
import com.sitionix.forgeai.domain.usecase.UpdateAgentWorkflow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ForgeAiInfrastructureAgentsControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TASK_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID REPOSITORY_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Mock
    private ListAgentProjects listAgentProjects;
    @Mock
    private CreateAgentProject createAgentProject;
    @Mock
    private DeleteAgentProject deleteAgentProject;
    @Mock
    private ImportAgentProjectRepository importAgentProjectRepository;
    @Mock
    private ListAgentProjectRepositories listAgentProjectRepositories;
    @Mock
    private CloneAgentProjectRepository cloneAgentProjectRepository;
    @Mock
    private RefreshAgentProjectRepository refreshAgentProjectRepository;
    @Mock
    private PullAgentProjectRepository pullAgentProjectRepository;
    @Mock
    private CreateAgentProjectTask createAgentProjectTask;
    @Mock
    private ListAgentProjectTasks listAgentProjectTasks;
    @Mock
    private GetAgentProjectTask getAgentProjectTask;
    @Mock
    private DeleteAgentProjectTask deleteAgentProjectTask;
    @Mock
    private GetAgentRuntime getAgentRuntime;
    @Mock
    private ListProjectAgentDefinitions listProjectAgentDefinitions;
    @Mock
    private CreateAgentDefinition createAgentDefinition;
    @Mock
    private GetAgentDefinition getAgentDefinition;
    @Mock
    private UpdateAgentDefinition updateAgentDefinition;
    @Mock
    private DeleteAgentDefinition deleteAgentDefinition;
    @Mock
    private ListAgentWorkflows listAgentWorkflows;
    @Mock
    private CreateAgentWorkflow createAgentWorkflow;
    @Mock
    private GetAgentWorkflow getAgentWorkflow;
    @Mock
    private UpdateAgentWorkflow updateAgentWorkflow;
    @Mock
    private DeleteAgentWorkflow deleteAgentWorkflow;
    @Mock
    private CreateAgentWorkflowRun createAgentWorkflowRun;
    @Mock
    private ListAgentWorkflowRuns listAgentWorkflowRuns;
    @Mock
    private GetAgentWorkflowRun getAgentWorkflowRun;
    @Mock
    private AgentProxyApiMapper mapper;

    private ForgeAiInfrastructureAgentsController controller;

    @BeforeEach
    void setUp() {
        this.controller = new ForgeAiInfrastructureAgentsController(
                this.listAgentProjects,
                this.createAgentProject,
                this.deleteAgentProject,
                this.importAgentProjectRepository,
                this.listAgentProjectRepositories,
                this.cloneAgentProjectRepository,
                this.refreshAgentProjectRepository,
                this.pullAgentProjectRepository,
                this.createAgentProjectTask,
                this.listAgentProjectTasks,
                this.getAgentProjectTask,
                this.deleteAgentProjectTask,
                this.getAgentRuntime,
                this.listProjectAgentDefinitions,
                this.createAgentDefinition,
                this.getAgentDefinition,
                this.updateAgentDefinition,
                this.deleteAgentDefinition,
                this.listAgentWorkflows,
                this.createAgentWorkflow,
                this.getAgentWorkflow,
                this.updateAgentWorkflow,
                this.deleteAgentWorkflow,
                this.createAgentWorkflowRun,
                this.listAgentWorkflowRuns,
                this.getAgentWorkflowRun,
                this.mapper
        );
    }

    @Test
    void listProjects() {
        final AgentProject project = this.project();
        final AgentProjectResponse response = this.projectResponse();
        when(this.listAgentProjects.execute()).thenReturn(List.of(project));
        when(this.mapper.toResponse(project)).thenReturn(response);

        final var actual = this.controller.listProjects();

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.listAgentProjects).execute();
        verify(this.mapper).toResponse(project);
    }

    @Test
    void createProject() {
        final AgentProjectRequest request = new AgentProjectRequest("Sitionix");
        final CreateAgentProjectCommand command = new CreateAgentProjectCommand("Sitionix");
        final AgentProject project = this.project();
        final AgentProjectResponse response = this.projectResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.createAgentProject.execute(command)).thenReturn(project);
        when(this.mapper.toResponse(project)).thenReturn(response);

        final var actual = this.controller.createProject(request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/infrastructure/agents/projects/" + PROJECT_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.createAgentProject).execute(command);
        verify(this.mapper).toResponse(project);
    }

    @Test
    void importProjectRepository() {
        final var request = new ImportAgentProjectRepositoryRequest("git@gitlab.com:company/service-a.git");
        final var command = new ImportAgentProjectRepositoryCommand("git@gitlab.com:company/service-a.git");
        final var repository = this.projectRepository();
        final var response = this.projectRepositoryResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.importAgentProjectRepository.execute(PROJECT_ID, command)).thenReturn(repository);
        when(this.mapper.toResponse(repository)).thenReturn(response);

        final var actual = this.controller.importProjectRepository(PROJECT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString())
                .isEqualTo("/api/v1/infrastructure/agents/projects/" + PROJECT_ID + "/repositories/" + REPOSITORY_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.importAgentProjectRepository).execute(PROJECT_ID, command);
        verify(this.mapper).toResponse(repository);
    }

    @Test
    void listProjectRepositories() {
        final var repository = this.projectRepository();
        final var response = this.projectRepositoryResponse();
        when(this.listAgentProjectRepositories.execute(PROJECT_ID)).thenReturn(List.of(repository));
        when(this.mapper.toResponse(repository)).thenReturn(response);

        final var actual = this.controller.listProjectRepositories(PROJECT_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.listAgentProjectRepositories).execute(PROJECT_ID);
        verify(this.mapper).toResponse(repository);
    }

    @Test
    void cloneProjectRepository() {
        final var repository = this.projectRepository();
        final var response = this.projectRepositoryResponse();
        when(this.cloneAgentProjectRepository.execute(PROJECT_ID, REPOSITORY_ID)).thenReturn(repository);
        when(this.mapper.toResponse(repository)).thenReturn(response);

        final var actual = this.controller.cloneProjectRepository(PROJECT_ID, REPOSITORY_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.cloneAgentProjectRepository).execute(PROJECT_ID, REPOSITORY_ID);
        verify(this.mapper).toResponse(repository);
    }

    @Test
    void pullProjectRepository() {
        final var repository = this.projectRepository();
        final var response = this.projectRepositoryResponse();
        when(this.pullAgentProjectRepository.execute(PROJECT_ID, REPOSITORY_ID)).thenReturn(repository);
        when(this.mapper.toResponse(repository)).thenReturn(response);

        final var actual = this.controller.pullProjectRepository(PROJECT_ID, REPOSITORY_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.pullAgentProjectRepository).execute(PROJECT_ID, REPOSITORY_ID);
        verify(this.mapper).toResponse(repository);
    }

    @Test
    void refreshProjectRepository() {
        final var repository = this.projectRepository();
        final var response = this.projectRepositoryResponse();
        when(this.refreshAgentProjectRepository.execute(PROJECT_ID, REPOSITORY_ID)).thenReturn(repository);
        when(this.mapper.toResponse(repository)).thenReturn(response);

        final var actual = this.controller.refreshProjectRepository(PROJECT_ID, REPOSITORY_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.refreshAgentProjectRepository).execute(PROJECT_ID, REPOSITORY_ID);
        verify(this.mapper).toResponse(repository);
    }

    @Test
    void createProjectTask() {
        final var request = new CreateAgentProjectTaskRequest("Check calculation", "Count letters.", WORKFLOW_ID, List.of(REPOSITORY_ID));
        final var command = new CreateAgentProjectTaskCommand("Check calculation", "Count letters.", WORKFLOW_ID, List.of(REPOSITORY_ID));
        final var task = this.task();
        final var response = this.taskResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.createAgentProjectTask.execute(PROJECT_ID, command)).thenReturn(task);
        when(this.mapper.toResponse(task)).thenReturn(response);

        final var actual = this.controller.createProjectTask(PROJECT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/infrastructure/agents/tasks/" + TASK_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.createAgentProjectTask).execute(PROJECT_ID, command);
        verify(this.mapper).toResponse(task);
    }

    @Test
    void listProjectTasks() {
        final var task = new AgentProjectTaskSummary(TASK_ID, PROJECT_ID, "Check calculation", WORKFLOW_ID, "Full Testing", RUN_ID, AgentWorkflowRunStatus.QUEUED, NOW, NOW);
        final var response = new AgentProjectTaskSummaryResponse(TASK_ID, PROJECT_ID, "Check calculation", WORKFLOW_ID, "Full Testing", RUN_ID, AgentWorkflowRunStatus.QUEUED, NOW, NOW);
        final var page = new AgentProjectTaskPage(List.of(task), 1, 10, 11, 2);
        final var pageResponse = new AgentProjectTaskPageResponse(List.of(response), 1, 10, 11, 2);
        when(this.listAgentProjectTasks.execute(PROJECT_ID, 1, 10)).thenReturn(page);
        when(this.mapper.toResponse(page)).thenReturn(pageResponse);

        final var actual = this.controller.listProjectTasks(PROJECT_ID, 1, 10);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(pageResponse);
        verify(this.listAgentProjectTasks).execute(PROJECT_ID, 1, 10);
        verify(this.mapper).toResponse(page);
    }

    @Test
    void getProjectTask() {
        final var task = this.task();
        final var response = this.taskResponse();
        when(this.getAgentProjectTask.execute(TASK_ID)).thenReturn(task);
        when(this.mapper.toResponse(task)).thenReturn(response);

        final var actual = this.controller.getProjectTask(TASK_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.getAgentProjectTask).execute(TASK_ID);
        verify(this.mapper).toResponse(task);
    }

    @Test
    void getRuntime() {
        final var runtime = new AgentRuntimeCatalog(List.of(new AgentRuntimeProvider("codex", "Codex", AgentRuntimeProviderStatus.READY, "codex/1", List.of())));
        final var response = new AgentRuntimeResponse(List.of(new AgentRuntimeProviderResponse("codex", "Codex", AgentRuntimeProviderStatus.READY, "codex/1", List.of())));
        when(this.getAgentRuntime.execute()).thenReturn(runtime);
        when(this.mapper.toResponse(runtime)).thenReturn(response);

        final var actual = this.controller.getRuntime();

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.getAgentRuntime).execute();
        verify(this.mapper).toResponse(runtime);
    }

    @Test
    void listProjectAgents() {
        final AgentDefinitionListItem item = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", null, NOW, NOW);
        final AgentDefinitionListResponse response = new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend", null, NOW, NOW);
        when(this.listProjectAgentDefinitions.execute(PROJECT_ID)).thenReturn(List.of(item));
        when(this.mapper.toResponse(item)).thenReturn(response);

        final var actual = this.controller.listProjectAgents(PROJECT_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.listProjectAgentDefinitions).execute(PROJECT_ID);
        verify(this.mapper).toResponse(item);
    }

    @Test
    void createAgent() {
        final AgentDefinitionRequest request = this.agentRequest();
        final SaveAgentDefinitionCommand command = this.agentCommand();
        final AgentDefinitionDetails agent = this.agent();
        final AgentDefinitionResponse response = this.agentResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.createAgentDefinition.execute(PROJECT_ID, command)).thenReturn(agent);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        final var actual = this.controller.createAgent(PROJECT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/infrastructure/agents/definitions/" + AGENT_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.createAgentDefinition).execute(PROJECT_ID, command);
        verify(this.mapper).toResponse(agent);
    }

    @Test
    void getAgent() {
        final AgentDefinitionDetails agent = this.agent();
        final AgentDefinitionResponse response = this.agentResponse();
        when(this.getAgentDefinition.execute(AGENT_ID)).thenReturn(agent);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        final var actual = this.controller.getAgent(AGENT_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.getAgentDefinition).execute(AGENT_ID);
        verify(this.mapper).toResponse(agent);
    }

    @Test
    void updateAgent() {
        final AgentDefinitionRequest request = this.agentRequest();
        final SaveAgentDefinitionCommand command = this.agentCommand();
        final AgentDefinitionDetails agent = this.agent();
        final AgentDefinitionResponse response = this.agentResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.updateAgentDefinition.execute(AGENT_ID, command)).thenReturn(agent);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        final var actual = this.controller.updateAgent(AGENT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.updateAgentDefinition).execute(AGENT_ID, command);
        verify(this.mapper).toResponse(agent);
    }

    @Test
    void listProjectWorkflows() {
        final AgentWorkflow workflow = this.workflow();
        final AgentWorkflowResponse response = this.workflowResponse();
        when(this.listAgentWorkflows.execute(PROJECT_ID)).thenReturn(List.of(workflow));
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.listProjectWorkflows(PROJECT_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.listAgentWorkflows).execute(PROJECT_ID);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void createWorkflow() {
        final AgentWorkflowRequest request = new AgentWorkflowRequest("Full Testing");
        final CreateAgentWorkflowCommand command = new CreateAgentWorkflowCommand("Full Testing");
        final AgentWorkflow workflow = this.workflow();
        final AgentWorkflowResponse response = this.workflowResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.createAgentWorkflow.execute(PROJECT_ID, command)).thenReturn(workflow);
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.createWorkflow(PROJECT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/infrastructure/agents/workflows/" + WORKFLOW_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.createAgentWorkflow).execute(PROJECT_ID, command);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void getWorkflow() {
        final AgentWorkflow workflow = this.workflow();
        final AgentWorkflowResponse response = this.workflowResponse();
        when(this.getAgentWorkflow.execute(WORKFLOW_ID)).thenReturn(workflow);
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.getWorkflow(WORKFLOW_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.getAgentWorkflow).execute(WORKFLOW_ID);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void updateWorkflow() {
        final SaveAgentWorkflowRequest request = new SaveAgentWorkflowRequest("Full Testing", List.of(), List.of(), null, null);
        final SaveAgentWorkflowCommand command = new SaveAgentWorkflowCommand("Full Testing", List.of(), List.of(), null);
        final AgentWorkflow workflow = this.workflow();
        final AgentWorkflowResponse response = this.workflowResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.updateAgentWorkflow.execute(WORKFLOW_ID, command)).thenReturn(workflow);
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.updateWorkflow(WORKFLOW_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.updateAgentWorkflow).execute(WORKFLOW_ID, command);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void createWorkflowRun() {
        final CreateAgentWorkflowRunRequest request = new CreateAgentWorkflowRunRequest("Review auth changes.");
        final CreateAgentWorkflowRunCommand command = new CreateAgentWorkflowRunCommand("Review auth changes.");
        final AgentWorkflowRun run = this.workflowRun();
        final AgentWorkflowRunResponse response = this.workflowRunResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.createAgentWorkflowRun.execute(WORKFLOW_ID, command)).thenReturn(run);
        when(this.mapper.toResponse(run)).thenReturn(response);

        final var actual = this.controller.createWorkflowRun(WORKFLOW_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/infrastructure/agents/workflow-runs/" + RUN_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.createAgentWorkflowRun).execute(WORKFLOW_ID, command);
        verify(this.mapper).toResponse(run);
    }

    @Test
    void listWorkflowRuns() {
        final AgentWorkflowRunSummary run = new AgentWorkflowRunSummary(RUN_ID, WORKFLOW_ID, null, "Full Testing", AgentWorkflowRunStatus.QUEUED, NOW, null, null);
        final AgentWorkflowRunSummaryResponse response = new AgentWorkflowRunSummaryResponse(RUN_ID, WORKFLOW_ID, null, "Full Testing", AgentWorkflowRunStatus.QUEUED, NOW, null, null);
        when(this.listAgentWorkflowRuns.execute(WORKFLOW_ID)).thenReturn(List.of(run));
        when(this.mapper.toResponse(run)).thenReturn(response);

        final var actual = this.controller.listWorkflowRuns(WORKFLOW_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.listAgentWorkflowRuns).execute(WORKFLOW_ID);
        verify(this.mapper).toResponse(run);
    }

    @Test
    void getWorkflowRun() {
        final AgentWorkflowRun run = this.workflowRun();
        final AgentWorkflowRunResponse response = this.workflowRunResponse();
        when(this.getAgentWorkflowRun.execute(RUN_ID)).thenReturn(run);
        when(this.mapper.toResponse(run)).thenReturn(response);

        final var actual = this.controller.getWorkflowRun(RUN_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.getAgentWorkflowRun).execute(RUN_ID);
        verify(this.mapper).toResponse(run);
    }

    private AgentProject project() {
        return new AgentProject(PROJECT_ID, "Sitionix", NOW, NOW);
    }

    private AgentProjectResponse projectResponse() {
        return new AgentProjectResponse(PROJECT_ID, "Sitionix", NOW, NOW);
    }

    private AgentProjectRepository projectRepository() {
        return new AgentProjectRepository(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                false,
                null,
                NOW
        );
    }

    private AgentProjectRepositoryResponse projectRepositoryResponse() {
        return new AgentProjectRepositoryResponse(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                false,
                null,
                NOW
        );
    }

    private AgentDefinitionRequest agentRequest() {
        return new AgentDefinitionRequest("Backend", "Do work.", null, null);
    }

    private SaveAgentDefinitionCommand agentCommand() {
        return new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{}"), null);
    }

    private AgentDefinitionDetails agent() {
        return new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), null, NOW, NOW);
    }

    private AgentDefinitionResponse agentResponse() {
        return new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, null, NOW, NOW);
    }

    private AgentWorkflow workflow() {
        return new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, NOW, NOW);
    }

    private AgentWorkflowResponse workflowResponse() {
        return new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, NOW, NOW);
    }

    private AgentWorkflowRun workflowRun() {
        return new AgentWorkflowRun(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.QUEUED,
                List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                null,
                null,
                NOW,
                null,
                null,
                java.util.List.of()
        );
    }

    private AgentWorkflowRunResponse workflowRunResponse() {
        return new AgentWorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.QUEUED,
                List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                null,
                null,
                NOW,
                null,
                null,
                java.util.List.of()
        );
    }

    private AgentProjectTask task() {
        return new AgentProjectTask(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                "Count letters.",
                WORKFLOW_ID,
                List.of(REPOSITORY_ID),
                List.of(new AgentWorkflowRunSummary(RUN_ID, WORKFLOW_ID, TASK_ID, "Full Testing", AgentWorkflowRunStatus.QUEUED, NOW, null, null)),
                NOW,
                NOW
        );
    }

    private AgentProjectTaskResponse taskResponse() {
        return new AgentProjectTaskResponse(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                "Count letters.",
                WORKFLOW_ID,
                List.of(REPOSITORY_ID),
                List.of(new AgentWorkflowRunSummaryResponse(RUN_ID, WORKFLOW_ID, TASK_ID, "Full Testing", AgentWorkflowRunStatus.QUEUED, NOW, null, null)),
                NOW,
                NOW
        );
    }
}
