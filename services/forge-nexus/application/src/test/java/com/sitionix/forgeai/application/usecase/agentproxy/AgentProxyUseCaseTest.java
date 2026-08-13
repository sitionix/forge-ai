package com.sitionix.forgeai.application.usecase.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProvider;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProviderStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentProxyUseCaseTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TASK_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID RUN_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Mock
    private ForgeAgentClient forgeAgentClient;

    @Test
    void listProjectsDelegatesToClient() {
        final var project = new AgentProject(PROJECT_ID, "Sitionix", NOW, NOW);
        when(this.forgeAgentClient.listProjects()).thenReturn(List.of(project));

        final var actual = new ListAgentProjectsUseCase(this.forgeAgentClient).execute();

        assertThat(actual).containsExactly(project);
        verify(this.forgeAgentClient).listProjects();
    }

    @Test
    void createProjectDelegatesToClient() {
        final var command = new CreateAgentProjectCommand("Sitionix");
        final var project = new AgentProject(PROJECT_ID, "Sitionix", NOW, NOW);
        when(this.forgeAgentClient.createProject(command)).thenReturn(project);

        final var actual = new CreateAgentProjectUseCase(this.forgeAgentClient).execute(command);

        assertThat(actual).isSameAs(project);
        verify(this.forgeAgentClient).createProject(command);
    }

    @Test
    void projectTaskUseCasesDelegateToClient() {
        final var command = new CreateAgentProjectTaskCommand("Check calculation", "Count letters.", WORKFLOW_ID);
        final var summary = new AgentProjectTaskSummary(TASK_ID, PROJECT_ID, "Check calculation", WORKFLOW_ID, "Full Testing", RUN_ID, AgentWorkflowRunStatus.QUEUED, NOW, NOW);
        final var task = new AgentProjectTask(TASK_ID, PROJECT_ID, "Check calculation", "Count letters.", WORKFLOW_ID, List.of(), NOW, NOW);
        when(this.forgeAgentClient.createProjectTask(PROJECT_ID, command)).thenReturn(task);
        when(this.forgeAgentClient.listProjectTasks(PROJECT_ID)).thenReturn(List.of(summary));
        when(this.forgeAgentClient.getProjectTask(TASK_ID)).thenReturn(task);

        assertThat(new CreateAgentProjectTaskUseCase(this.forgeAgentClient).execute(PROJECT_ID, command)).isSameAs(task);
        assertThat(new ListAgentProjectTasksUseCase(this.forgeAgentClient).execute(PROJECT_ID)).containsExactly(summary);
        assertThat(new GetAgentProjectTaskUseCase(this.forgeAgentClient).execute(TASK_ID)).isSameAs(task);
        verify(this.forgeAgentClient).createProjectTask(PROJECT_ID, command);
        verify(this.forgeAgentClient).listProjectTasks(PROJECT_ID);
        verify(this.forgeAgentClient).getProjectTask(TASK_ID);
    }

    @Test
    void getRuntimeDelegatesToClient() {
        final var runtime = new AgentRuntimeCatalog(List.of(new AgentRuntimeProvider("codex", "Codex", AgentRuntimeProviderStatus.READY, "codex/1", List.of())));
        when(this.forgeAgentClient.getRuntime()).thenReturn(runtime);

        final var actual = new GetAgentRuntimeUseCase(this.forgeAgentClient).execute();

        assertThat(actual).isSameAs(runtime);
        verify(this.forgeAgentClient).getRuntime();
    }

    @Test
    void listProjectAgentsDelegatesToClient() {
        final var agent = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", null, NOW, NOW);
        when(this.forgeAgentClient.listProjectAgents(PROJECT_ID)).thenReturn(List.of(agent));

        final var actual = new ListProjectAgentDefinitionsUseCase(this.forgeAgentClient).execute(PROJECT_ID);

        assertThat(actual).containsExactly(agent);
        verify(this.forgeAgentClient).listProjectAgents(PROJECT_ID);
    }

    @Test
    void agentDefinitionUseCasesDelegateToClient() {
        final var command = new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{}"), null);
        final var agent = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), null, NOW, NOW);
        when(this.forgeAgentClient.createAgent(PROJECT_ID, command)).thenReturn(agent);
        when(this.forgeAgentClient.getAgent(AGENT_ID)).thenReturn(agent);
        when(this.forgeAgentClient.updateAgent(AGENT_ID, command)).thenReturn(agent);

        assertThat(new CreateAgentDefinitionUseCase(this.forgeAgentClient).execute(PROJECT_ID, command)).isSameAs(agent);
        assertThat(new GetAgentDefinitionUseCase(this.forgeAgentClient).execute(AGENT_ID)).isSameAs(agent);
        assertThat(new UpdateAgentDefinitionUseCase(this.forgeAgentClient).execute(AGENT_ID, command)).isSameAs(agent);
        verify(this.forgeAgentClient).createAgent(PROJECT_ID, command);
        verify(this.forgeAgentClient).getAgent(AGENT_ID);
        verify(this.forgeAgentClient).updateAgent(AGENT_ID, command);
    }

    @Test
    void workflowUseCasesDelegateToClient() {
        final var node = new Node(NODE_ID, AGENT_ID, List.of(), new NodePosition(1.0, 2.0));
        final var createCommand = new CreateAgentWorkflowCommand("Full Testing");
        final var saveCommand = new SaveAgentWorkflowCommand("Full Testing", List.of(node));
        final var workflow = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(node), NOW, NOW);
        when(this.forgeAgentClient.listProjectWorkflows(PROJECT_ID)).thenReturn(List.of(workflow));
        when(this.forgeAgentClient.createWorkflow(PROJECT_ID, createCommand)).thenReturn(workflow);
        when(this.forgeAgentClient.getWorkflow(WORKFLOW_ID)).thenReturn(workflow);
        when(this.forgeAgentClient.updateWorkflow(WORKFLOW_ID, saveCommand)).thenReturn(workflow);

        assertThat(new ListAgentWorkflowsUseCase(this.forgeAgentClient).execute(PROJECT_ID)).containsExactly(workflow);
        assertThat(new CreateAgentWorkflowUseCase(this.forgeAgentClient).execute(PROJECT_ID, createCommand)).isSameAs(workflow);
        assertThat(new GetAgentWorkflowUseCase(this.forgeAgentClient).execute(WORKFLOW_ID)).isSameAs(workflow);
        assertThat(new UpdateAgentWorkflowUseCase(this.forgeAgentClient).execute(WORKFLOW_ID, saveCommand)).isSameAs(workflow);
        verify(this.forgeAgentClient).listProjectWorkflows(PROJECT_ID);
        verify(this.forgeAgentClient).createWorkflow(PROJECT_ID, createCommand);
        verify(this.forgeAgentClient).getWorkflow(WORKFLOW_ID);
        verify(this.forgeAgentClient).updateWorkflow(WORKFLOW_ID, saveCommand);
    }
}
