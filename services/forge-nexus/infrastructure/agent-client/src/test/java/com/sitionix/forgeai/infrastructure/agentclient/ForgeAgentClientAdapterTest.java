package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
        this.adapter = new ForgeAgentClientAdapter(this.httpClient, this.mapper, this.executor);
        this.executeSuppliedCalls();
    }

    @Test
    void listProjectsDelegatesThroughExecutorAndMapper() {
        final var upstreamResponse = new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        final var expected = new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        when(this.httpClient.listProjects()).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "projects")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.listProjects()).containsExactly(expected);
    }

    @Test
    void createProjectMapsRequestExecutesCallAndMapsResponse() {
        final var command = new CreateAgentProjectCommand("Sitionix");
        final var request = new AgentProjectRequest("Sitionix");
        final var upstreamResponse = new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        final var expected = new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createProject(request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        assertThat(this.adapter.createProject(command)).isEqualTo(expected);
    }

    @Test
    void agentCallsUseTypedRequestResponseFlow() {
        final var listResponse = new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend", CREATED, UPDATED);
        final var listItem = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", CREATED, UPDATED);
        final var command = new SaveAgentDefinitionCommand("Backend", "Do work.", new AgentOutputSchemaDocument("{\"type\":\"object\"}"));
        final var request = new AgentDefinitionRequest("Backend", "Do work.", null);
        final var upstreamResponse = new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, CREATED, UPDATED);
        final var details = new AgentDefinitionDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", new AgentOutputSchemaDocument("{}"), CREATED, UPDATED);
        when(this.httpClient.listProjectAgents(PROJECT_ID)).thenReturn(List.of(listResponse));
        when(this.mapper.requireList(List.of(listResponse), "agents")).thenReturn(List.of(listResponse));
        when(this.mapper.toDomain(listResponse)).thenReturn(listItem);
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createAgent(PROJECT_ID, request)).thenReturn(upstreamResponse);
        when(this.httpClient.getAgent(AGENT_ID)).thenReturn(upstreamResponse);
        when(this.httpClient.updateAgent(AGENT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(details);

        assertThat(this.adapter.listProjectAgents(PROJECT_ID)).containsExactly(listItem);
        assertThat(this.adapter.createAgent(PROJECT_ID, command)).isEqualTo(details);
        assertThat(this.adapter.getAgent(AGENT_ID)).isEqualTo(details);
        assertThat(this.adapter.updateAgent(AGENT_ID, command)).isEqualTo(details);
    }

    @Test
    void workflowCallsUseTypedRequestResponseFlow() {
        final var createCommand = new CreateAgentWorkflowCommand("Full Testing");
        final var saveCommand = new SaveAgentWorkflowCommand("Full Testing", List.of());
        final var createRequest = new AgentWorkflowRequest("Full Testing");
        final var saveRequest = new SaveAgentWorkflowRequest("Full Testing", List.of());
        final var upstreamResponse = new AgentWorkflowResponse(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        final var workflow = new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(), CREATED, UPDATED);
        when(this.httpClient.listProjectWorkflows(PROJECT_ID)).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "workflows")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(workflow);
        when(this.mapper.toRequest(createCommand)).thenReturn(createRequest);
        when(this.mapper.toRequest(saveCommand)).thenReturn(saveRequest);
        when(this.httpClient.createWorkflow(PROJECT_ID, createRequest)).thenReturn(upstreamResponse);
        when(this.httpClient.getWorkflow(WORKFLOW_ID)).thenReturn(upstreamResponse);
        when(this.httpClient.updateWorkflow(WORKFLOW_ID, saveRequest)).thenReturn(upstreamResponse);

        assertThat(this.adapter.listProjectWorkflows(PROJECT_ID)).containsExactly(workflow);
        assertThat(this.adapter.createWorkflow(PROJECT_ID, createCommand)).isEqualTo(workflow);
        assertThat(this.adapter.getWorkflow(WORKFLOW_ID)).isEqualTo(workflow);
        assertThat(this.adapter.updateWorkflow(WORKFLOW_ID, saveCommand)).isEqualTo(workflow);

        final InOrder inOrder = inOrder(this.executor, this.mapper, this.httpClient);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.httpClient).listProjectWorkflows(PROJECT_ID);
    }

    @SuppressWarnings("unchecked")
    private void executeSuppliedCalls() {
        when(this.executor.execute(any(Supplier.class))).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }
}
