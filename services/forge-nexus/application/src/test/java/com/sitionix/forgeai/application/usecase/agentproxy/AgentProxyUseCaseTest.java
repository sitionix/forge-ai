package com.sitionix.forgeai.application.usecase.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
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
    void listProjectAgentsDelegatesToClient() {
        final var agent = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", List.of(), NOW, NOW);
        when(this.forgeAgentClient.listProjectAgents(PROJECT_ID)).thenReturn(List.of(agent));

        final var actual = new ListProjectAgentDefinitionsUseCase(this.forgeAgentClient).execute(PROJECT_ID);

        assertThat(actual).containsExactly(agent);
        verify(this.forgeAgentClient).listProjectAgents(PROJECT_ID);
    }

    @Test
    void agentDefinitionUseCasesDelegateToClient() {
        final var command = new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{}"), List.of());
        final var agent = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), List.of(), NOW, NOW);
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
}
