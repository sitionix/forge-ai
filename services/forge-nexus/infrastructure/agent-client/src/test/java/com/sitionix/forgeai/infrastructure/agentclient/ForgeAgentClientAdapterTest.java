package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDependencySummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
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
    private static final UUID DEPENDENCY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
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

        final var actual = this.adapter.listProjects();

        assertThat(actual).containsExactly(expected);
        final InOrder inOrder = inOrder(this.executor, this.httpClient, this.mapper);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.httpClient).listProjects();
        inOrder.verify(this.mapper).requireList(List.of(upstreamResponse), "projects");
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
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

        final var actual = this.adapter.createProject(command);

        assertThat(actual).isEqualTo(expected);
        final InOrder inOrder = inOrder(this.executor, this.mapper, this.httpClient);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.mapper).toRequest(command);
        inOrder.verify(this.httpClient).createProject(request);
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void listProjectAgentsDelegatesThroughExecutorAndMapper() {
        final var upstreamResponse = new AgentDefinitionListResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                List.of(),
                CREATED,
                UPDATED
        );
        final var expected = new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", List.of(), CREATED, UPDATED);
        when(this.httpClient.listProjectAgents(PROJECT_ID)).thenReturn(List.of(upstreamResponse));
        when(this.mapper.requireList(List.of(upstreamResponse), "agents")).thenReturn(List.of(upstreamResponse));
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        final var actual = this.adapter.listProjectAgents(PROJECT_ID);

        assertThat(actual).containsExactly(expected);
        final InOrder inOrder = inOrder(this.executor, this.httpClient, this.mapper);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.httpClient).listProjectAgents(PROJECT_ID);
        inOrder.verify(this.mapper).requireList(List.of(upstreamResponse), "agents");
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void createAgentMapsRequestExecutesCallAndMapsResponse() {
        final var command = this.command();
        final var request = this.request();
        final var upstreamResponse = this.upstreamAgentResponse();
        final var expected = this.agentDetails();
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.createAgent(PROJECT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        final var actual = this.adapter.createAgent(PROJECT_ID, command);

        assertThat(actual).isEqualTo(expected);
        final InOrder inOrder = inOrder(this.executor, this.mapper, this.httpClient);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.mapper).toRequest(command);
        inOrder.verify(this.httpClient).createAgent(PROJECT_ID, request);
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void getAgentExecutesCallAndMapsResponse() {
        final var upstreamResponse = this.upstreamAgentResponse();
        final var expected = this.agentDetails();
        when(this.httpClient.getAgent(AGENT_ID)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        final var actual = this.adapter.getAgent(AGENT_ID);

        assertThat(actual).isEqualTo(expected);
        final InOrder inOrder = inOrder(this.executor, this.httpClient, this.mapper);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.httpClient).getAgent(AGENT_ID);
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @Test
    void updateAgentMapsRequestExecutesCallAndMapsResponse() {
        final var command = this.command();
        final var request = this.request();
        final var upstreamResponse = this.upstreamAgentResponse();
        final var expected = this.agentDetails();
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.updateAgent(AGENT_ID, request)).thenReturn(upstreamResponse);
        when(this.mapper.toDomain(upstreamResponse)).thenReturn(expected);

        final var actual = this.adapter.updateAgent(AGENT_ID, command);

        assertThat(actual).isEqualTo(expected);
        final InOrder inOrder = inOrder(this.executor, this.mapper, this.httpClient);
        inOrder.verify(this.executor).execute(any());
        inOrder.verify(this.mapper).toRequest(command);
        inOrder.verify(this.httpClient).updateAgent(AGENT_ID, request);
        inOrder.verify(this.mapper).toDomain(upstreamResponse);
    }

    @SuppressWarnings("unchecked")
    private void executeSuppliedCalls() {
        when(this.executor.execute(any(Supplier.class))).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    private SaveAgentDefinitionCommand command() {
        return new SaveAgentDefinitionCommand(
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of(DEPENDENCY_ID)
        );
    }

    private AgentDefinitionRequest request() {
        return new AgentDefinitionRequest("Backend", "Do work.", null, List.of(DEPENDENCY_ID));
    }

    private AgentDefinitionResponse upstreamAgentResponse() {
        return new AgentDefinitionResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", null, List.of(), CREATED, UPDATED);
    }

    private AgentDefinitionDetails agentDetails() {
        return new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of(new AgentDependencySummary(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );
    }
}
