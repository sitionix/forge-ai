package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.api.agentproxy.AgentDefinitionListResponse;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRequest;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowResponse;
import com.sitionix.forgeai.api.agentproxy.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.usecase.CreateAgentDefinition;
import com.sitionix.forgeai.domain.usecase.CreateAgentProject;
import com.sitionix.forgeai.domain.usecase.CreateAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.GetAgentDefinition;
import com.sitionix.forgeai.domain.usecase.GetAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.ListAgentProjects;
import com.sitionix.forgeai.domain.usecase.ListAgentWorkflows;
import com.sitionix.forgeai.domain.usecase.ListProjectAgentDefinitions;
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
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Mock
    private ListAgentProjects listAgentProjects;
    @Mock
    private CreateAgentProject createAgentProject;
    @Mock
    private ListProjectAgentDefinitions listProjectAgentDefinitions;
    @Mock
    private CreateAgentDefinition createAgentDefinition;
    @Mock
    private GetAgentDefinition getAgentDefinition;
    @Mock
    private UpdateAgentDefinition updateAgentDefinition;
    @Mock
    private ListAgentWorkflows listAgentWorkflows;
    @Mock
    private CreateAgentWorkflow createAgentWorkflow;
    @Mock
    private GetAgentWorkflow getAgentWorkflow;
    @Mock
    private UpdateAgentWorkflow updateAgentWorkflow;
    @Mock
    private AgentProxyApiMapper mapper;

    private ForgeAiInfrastructureAgentsController controller;

    @BeforeEach
    void setUp() {
        this.controller = new ForgeAiInfrastructureAgentsController(
                this.listAgentProjects,
                this.createAgentProject,
                this.listProjectAgentDefinitions,
                this.createAgentDefinition,
                this.getAgentDefinition,
                this.updateAgentDefinition,
                this.listAgentWorkflows,
                this.createAgentWorkflow,
                this.getAgentWorkflow,
                this.updateAgentWorkflow,
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
    void listProjectAgents() {
        final AgentDefinitionListItem item = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", NOW, NOW);
        final AgentDefinitionListResponse response = new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend", NOW, NOW);
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
    void locallyInvalidAgentRequestDoesNotCallUseCase() {
        final AgentDefinitionRequest request = this.agentRequest();
        when(this.mapper.toCommand(request)).thenThrow(new IllegalArgumentException("Output schema must be a JSON object."));

        assertThatThrownBy(() -> this.controller.createAgent(PROJECT_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");

        verifyNoInteractions(this.createAgentDefinition);
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
        final SaveAgentWorkflowRequest request = new SaveAgentWorkflowRequest("Full Testing", List.of());
        final SaveAgentWorkflowCommand command = new SaveAgentWorkflowCommand("Full Testing", List.of());
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

    private AgentProject project() {
        return new AgentProject(PROJECT_ID, "Sitionix", NOW, NOW);
    }

    private AgentProjectResponse projectResponse() {
        return new AgentProjectResponse(PROJECT_ID, "Sitionix", NOW, NOW);
    }

    private AgentDefinitionRequest agentRequest() {
        return new AgentDefinitionRequest("Backend", "Do work.", null);
    }

    private SaveAgentDefinitionCommand agentCommand() {
        return new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{}"));
    }

    private AgentDefinitionDetails agent() {
        return new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), NOW, NOW);
    }

    private AgentDefinitionResponse agentResponse() {
        return new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, NOW, NOW);
    }

    private AgentWorkflow workflow() {
        return new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), NOW, NOW);
    }

    private AgentWorkflowResponse workflowResponse() {
        return new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), NOW, NOW);
    }
}
