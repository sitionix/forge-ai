package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitState;
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
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeProviderResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateProjectTaskRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryGitStateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForgeAgentClientAdapterTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TASK_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID REPOSITORY_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant CREATED = Instant.parse("2026-08-04T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-04T00:01:00Z");

    @Mock
    private ForgeAgentHttpClient httpClient;
    @Mock
    private ForgeAgentClientMapper mapper;
    @Mock
    private ForgeAgentClientCallExecutor executor;

    private ForgeAgentClientAdapter adapter;

    @BeforeEach
    void setUp() {
        when(this.executor.execute(ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> invocation.<Supplier<Object>>getArgument(0).get());
        this.adapter = new ForgeAgentClientAdapter(this.httpClient, this.mapper, this.executor);
    }

    @Test
    void listProjectsExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        final var expected = new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        when(this.httpClient.listProjects()).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "projects")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.listProjects()).containsExactly(expected);

        final InOrder inOrder = inOrder(this.executor, this.httpClient, this.mapper);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.httpClient).listProjects();
        inOrder.verify(this.mapper).requireList(List.of(upstreamResponse), "projects");
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void createProjectMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new CreateAgentProjectCommand("Sitionix");
        final var request = new AgentProjectRequest("Sitionix");
        final var upstreamResponse = new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        final var expected = new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createProject(request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.createProject(command)).isEqualTo(expected);

        final InOrder inOrder = inOrder(this.mapper, this.executor, this.httpClient);
        inOrder.verify(this.mapper).toRequest(command);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.httpClient).createProject(request);
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void deleteProjectExecutesTypedClientCall() {
        this.adapter.deleteProject(PROJECT_ID);

        verify(this.executor).execute(any());
        verify(this.httpClient).deleteProject(PROJECT_ID);
    }

    @Test
    void importProjectRepositoryMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new ImportAgentProjectRepositoryCommand("git@gitlab.com:company/service-a.git");
        final var request = new ImportProjectRepositoryRequest("git@gitlab.com:company/service-a.git");
        final var upstreamResponse = new ProjectRepositoryResponse(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                false,
                null,
                CREATED
        );
        final var expected = new AgentProjectRepository(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                false,
                null,
                CREATED
        );
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.importProjectRepository(PROJECT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.importProjectRepository(PROJECT_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).importProjectRepository(PROJECT_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void listProjectRepositoriesExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new ProjectRepositoryResponse(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                false,
                null,
                CREATED
        );
        final var expected = new AgentProjectRepository(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                false,
                null,
                CREATED
        );
        when(this.httpClient.listProjectRepositories(PROJECT_ID)).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "repositories")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.listProjectRepositories(PROJECT_ID)).containsExactly(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).listProjectRepositories(PROJECT_ID);
        verify(this.mapper).requireList(List.of(upstreamResponse), "repositories");
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void cloneProjectRepositoryExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new ProjectRepositoryResponse(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                true,
                null,
                CREATED
        );
        final var expected = new AgentProjectRepository(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                true,
                null,
                CREATED
        );
        when(this.httpClient.cloneProjectRepository(PROJECT_ID, REPOSITORY_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.cloneProjectRepository(PROJECT_ID, REPOSITORY_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).cloneProjectRepository(PROJECT_ID, REPOSITORY_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void pullProjectRepositoryExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new ProjectRepositoryResponse(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                true,
                null,
                CREATED
        );
        final var expected = new AgentProjectRepository(
                REPOSITORY_ID,
                PROJECT_ID,
                "service-a",
                true,
                null,
                CREATED
        );
        when(this.httpClient.pullProjectRepository(PROJECT_ID, REPOSITORY_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.pullProjectRepository(PROJECT_ID, REPOSITORY_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).pullProjectRepository(PROJECT_ID, REPOSITORY_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void createProjectTaskMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var repositoryIds = List.of(REPOSITORY_ID);
        final var command = new CreateAgentProjectTaskCommand("Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds);
        final var request = new CreateProjectTaskRequest("Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds);
        final var upstreamResponse = new ProjectTaskResponse(TASK_ID, PROJECT_ID, "Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds, List.of(), CREATED, UPDATED);
        final var expected = new AgentProjectTask(TASK_ID, PROJECT_ID, "Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds, List.of(), CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createProjectTask(PROJECT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.createProjectTask(PROJECT_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).createProjectTask(PROJECT_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void listProjectTasksExecutesTypedClientCallAndMapsResponse() {
        final var summaryResponse = new ProjectTaskSummaryResponse(TASK_ID, PROJECT_ID, "Check calculation", WORKFLOW_ID, "Full Testing", RUN_ID, AgentWorkflowRunStatus.QUEUED, CREATED, UPDATED);
        final var upstreamResponse = new ProjectTaskPageResponse(List.of(summaryResponse), 2, 10, 21, 3);
        final var expected = new AgentProjectTaskSummary(TASK_ID, PROJECT_ID, "Check calculation", WORKFLOW_ID, "Full Testing", RUN_ID, AgentWorkflowRunStatus.QUEUED, CREATED, UPDATED);
        final var expectedPage = new AgentProjectTaskPage(List.of(expected), 2, 10, 21, 3);
        when(this.httpClient.listProjectTasks(PROJECT_ID, 2, 10)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expectedPage);

        assertThat(this.adapter.listProjectTasks(PROJECT_ID, 2, 10)).isEqualTo(expectedPage);

        verify(this.executor).execute(any());
        verify(this.httpClient).listProjectTasks(PROJECT_ID, 2, 10);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void getProjectTaskExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new ProjectTaskResponse(TASK_ID, PROJECT_ID, "Check calculation", "Count letters.", WORKFLOW_ID, List.of(REPOSITORY_ID), List.of(), CREATED, UPDATED);
        final var expected = new AgentProjectTask(TASK_ID, PROJECT_ID, "Check calculation", "Count letters.", WORKFLOW_ID, List.of(REPOSITORY_ID), List.of(), CREATED, UPDATED);
        when(this.httpClient.getProjectTask(TASK_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.getProjectTask(TASK_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).getProjectTask(TASK_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void deleteProjectTaskExecutesTypedClientCall() {
        this.adapter.deleteProjectTask(TASK_ID);

        verify(this.executor).execute(any());
        verify(this.httpClient).deleteProjectTask(TASK_ID);
    }

    @Test
    void getRuntimeExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentRuntimeResponse(List.of(new AgentRuntimeProviderResponse("codex", "Codex", AgentRuntimeProviderStatus.READY, "codex/1", List.of())));
        final var expected = new AgentRuntimeCatalog(List.of(new AgentRuntimeProvider("codex", "Codex", AgentRuntimeProviderStatus.READY, "codex/1", List.of())));
        when(this.httpClient.getRuntime()).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.getRuntime()).isEqualTo(expected);

        final InOrder inOrder = inOrder(this.executor, this.httpClient, this.mapper);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.httpClient).getRuntime();
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void listProjectAgentsExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend", null, CREATED, UPDATED);
        final var expected = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", null, CREATED, UPDATED);
        when(this.httpClient.listProjectAgents(PROJECT_ID)).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "agents")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.listProjectAgents(PROJECT_ID)).containsExactly(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).listProjectAgents(PROJECT_ID);
        verify(this.mapper).requireList(List.of(upstreamResponse), "agents");
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void createAgentMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{\"type\":\"object\"}"), null);
        final var request = new AgentDefinitionRequest("Backend", "Do work.", null, null);
        final var upstreamResponse = new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, null, CREATED, UPDATED);
        final var expected = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), null, CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createAgent(PROJECT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.createAgent(PROJECT_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).createAgent(PROJECT_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void getAgentExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, null, CREATED, UPDATED);
        final var expected = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), null, CREATED, UPDATED);
        when(this.httpClient.getAgent(AGENT_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.getAgent(AGENT_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).getAgent(AGENT_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void updateAgentMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{\"type\":\"object\"}"), null);
        final var request = new AgentDefinitionRequest("Backend", "Do work.", null, null);
        final var upstreamResponse = new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, null, CREATED, UPDATED);
        final var expected = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), null, CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.updateAgent(AGENT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.updateAgent(AGENT_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).updateAgent(AGENT_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void deleteAgentExecutesTypedClientCall() {
        this.adapter.deleteAgent(AGENT_ID);

        verify(this.executor).execute(any());
        verify(this.httpClient).deleteAgent(AGENT_ID);
    }

    @Test
    void listProjectWorkflowsExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        when(this.httpClient.listProjectWorkflows(PROJECT_ID)).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "workflows")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.listProjectWorkflows(PROJECT_ID)).containsExactly(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).listProjectWorkflows(PROJECT_ID);
        verify(this.mapper).requireList(List.of(upstreamResponse), "workflows");
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void createWorkflowMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new CreateAgentWorkflowCommand("Full Testing");
        final var request = new AgentWorkflowRequest("Full Testing");
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createWorkflow(PROJECT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.createWorkflow(PROJECT_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).createWorkflow(PROJECT_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void getWorkflowExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        when(this.httpClient.getWorkflow(WORKFLOW_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.getWorkflow(WORKFLOW_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).getWorkflow(WORKFLOW_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void updateWorkflowMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new SaveAgentWorkflowCommand("Full Testing", List.of(), List.of(), null);
        final var request = new SaveAgentWorkflowRequest("Full Testing", List.of(), List.of(), null);
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), List.of(), null, CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.updateWorkflow(WORKFLOW_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.updateWorkflow(WORKFLOW_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).updateWorkflow(WORKFLOW_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void deleteWorkflowExecutesTypedClientCall() {
        this.adapter.deleteWorkflow(WORKFLOW_ID);

        verify(this.executor).execute(any());
        verify(this.httpClient).deleteWorkflow(WORKFLOW_ID);
    }

    @Test
    void createWorkflowRunMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new CreateAgentWorkflowRunCommand("Review auth changes.");
        final var request = new CreateWorkflowRunRequest("Review auth changes.");
        final var upstreamResponse = new WorkflowRunResponse(
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
                CREATED,
                null,
                null,
                java.util.List.of()
        );
        final var expected = new AgentWorkflowRun(
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
                CREATED,
                null,
                null,
                java.util.List.of()
        );
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createWorkflowRun(WORKFLOW_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.createWorkflowRun(WORKFLOW_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).createWorkflowRun(WORKFLOW_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void listWorkflowRunsExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new WorkflowRunSummaryResponse(RUN_ID, WORKFLOW_ID, null, "Full Testing", AgentWorkflowRunStatus.QUEUED, CREATED, null, null);
        final var expected = new AgentWorkflowRunSummary(RUN_ID, WORKFLOW_ID, null, "Full Testing", AgentWorkflowRunStatus.QUEUED, CREATED, null, null);
        when(this.httpClient.listWorkflowRuns(WORKFLOW_ID)).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "workflow runs")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.listWorkflowRuns(WORKFLOW_ID)).containsExactly(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).listWorkflowRuns(WORKFLOW_ID);
        verify(this.mapper).requireList(List.of(upstreamResponse), "workflow runs");
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void getWorkflowRunExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new WorkflowRunResponse(
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
                CREATED,
                null,
                null,
                java.util.List.of()
        );
        final var expected = new AgentWorkflowRun(
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
                CREATED,
                null,
                null,
                java.util.List.of()
        );
        when(this.httpClient.getWorkflowRun(RUN_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.getWorkflowRun(RUN_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).getWorkflowRun(RUN_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }
}
