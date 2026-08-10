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
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
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
    void listProjectAgentsExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend", CREATED, UPDATED);
        final var expected = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", CREATED, UPDATED);
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
        final var command = new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{\"type\":\"object\"}"));
        final var request = new AgentDefinitionRequest("Backend", "Do work.", null);
        final var upstreamResponse = new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, CREATED, UPDATED);
        final var expected = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), CREATED, UPDATED);
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
        final var upstreamResponse = new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, CREATED, UPDATED);
        final var expected = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), CREATED, UPDATED);
        when(this.httpClient.getAgent(AGENT_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.getAgent(AGENT_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).getAgent(AGENT_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void updateAgentMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{\"type\":\"object\"}"));
        final var request = new AgentDefinitionRequest("Backend", "Do work.", null);
        final var upstreamResponse = new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, CREATED, UPDATED);
        final var expected = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), CREATED, UPDATED);
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
    void listProjectWorkflowsExecutesTypedClientCallAndMapsResponse() {
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
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
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
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
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        when(this.httpClient.getWorkflow(WORKFLOW_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.getWorkflow(WORKFLOW_ID)).isEqualTo(expected);

        verify(this.executor).execute(any());
        verify(this.httpClient).getWorkflow(WORKFLOW_ID);
        verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void updateWorkflowMapsRequestExecutesTypedClientCallAndMapsResponse() {
        final var command = new SaveAgentWorkflowCommand("Full Testing", List.of());
        final var request = new SaveAgentWorkflowRequest("Full Testing", List.of());
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        final var expected = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.updateWorkflow(WORKFLOW_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.updateWorkflow(WORKFLOW_ID, command)).isEqualTo(expected);

        verify(this.mapper).toRequest(command);
        verify(this.executor).execute(any());
        verify(this.httpClient).updateWorkflow(WORKFLOW_ID, request);
        verify(this.mapper).toDomain(upstreamResponse);
    }
}
