package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.api.agentproxy.AgentDefinitionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.usecase.CreateAgentDefinition;
import com.sitionix.forgeai.domain.usecase.CreateAgentProject;
import com.sitionix.forgeai.domain.usecase.GetAgentDefinition;
import com.sitionix.forgeai.domain.usecase.ListAgentProjects;
import com.sitionix.forgeai.domain.usecase.ListProjectAgentDefinitions;
import com.sitionix.forgeai.domain.usecase.UpdateAgentDefinition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForgeAiInfrastructureAgentsControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
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
                this.mapper
        );
    }

    @Test
    void listProjectsDelegatesAndMapsResponse() {
        final var project = new AgentProject(PROJECT_ID, "Sitionix", NOW, NOW);
        final var response = new AgentProjectResponse(PROJECT_ID, "Sitionix", NOW, NOW);
        when(this.listAgentProjects.execute()).thenReturn(List.of(project));
        when(this.mapper.toResponse(project)).thenReturn(response);

        final var actual = this.controller.listProjects();

        assertThat(actual.getBody()).containsExactly(response);
        verify(this.listAgentProjects).execute();
        verify(this.mapper).toResponse(project);
    }

    @Test
    void createProjectMapsRequestDelegatesAndReturnsCreated() {
        final var request = new AgentProjectRequest("Sitionix");
        final var command = new CreateAgentProjectCommand("Sitionix");
        final var project = new AgentProject(PROJECT_ID, "Sitionix", NOW, NOW);
        final var response = new AgentProjectResponse(PROJECT_ID, "Sitionix", NOW, NOW);
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.createAgentProject.execute(command)).thenReturn(project);
        when(this.mapper.toResponse(project)).thenReturn(response);

        final var actual = this.controller.createProject(request);

        assertThat(actual.getStatusCode().value()).isEqualTo(201);
        assertThat(actual.getHeaders().getLocation().toString()).endsWith("/api/v1/infrastructure/agents/projects/" + PROJECT_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.createAgentProject).execute(command);
    }

    @Test
    void createAgentMapsRequestDelegatesAndReturnsCreated() {
        final var request = this.request();
        final var command = this.command();
        final var agent = this.agent();
        final var response = this.response();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.createAgentDefinition.execute(PROJECT_ID, command)).thenReturn(agent);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        final var actual = this.controller.createAgent(PROJECT_ID, request);

        assertThat(actual.getStatusCode().value()).isEqualTo(201);
        assertThat(actual.getHeaders().getLocation().toString()).endsWith("/api/v1/infrastructure/agents/definitions/" + AGENT_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.createAgentDefinition).execute(PROJECT_ID, command);
    }

    @Test
    void getAndUpdateAgentDelegateThroughUseCases() {
        final var request = this.request();
        final var command = this.command();
        final var agent = this.agent();
        final var response = this.response();
        when(this.getAgentDefinition.execute(AGENT_ID)).thenReturn(agent);
        when(this.updateAgentDefinition.execute(AGENT_ID, command)).thenReturn(agent);
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        assertThat(this.controller.getAgent(AGENT_ID).getBody()).isSameAs(response);
        assertThat(this.controller.updateAgent(AGENT_ID, request).getBody()).isSameAs(response);
        verify(this.getAgentDefinition).execute(AGENT_ID);
        verify(this.updateAgentDefinition).execute(AGENT_ID, command);
    }

    private AgentDefinitionRequest request() {
        return new AgentDefinitionRequest("Backend", "Do work.", null, List.of());
    }

    private SaveAgentDefinitionCommand command() {
        return new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{}"), List.of());
    }

    private AgentDefinitionDetails agent() {
        return new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), List.of(), NOW, NOW);
    }

    private AgentDefinitionResponse response() {
        return new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, List.of(), NOW, NOW);
    }
}
