package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.HttpMessageConversionException;

@ExtendWith(MockitoExtension.class)
class ForgeAgentClientAdapterTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock
    private ForgeAgentHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void delegatesListProjectsThroughExecutorAndMapper() {
        final var adapter = this.adapter();
        when(this.httpClient.listProjects()).thenReturn(List.of(new AgentProjectResponse(
                PROJECT_ID,
                "Sitionix",
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
        )));

        final var actual = adapter.listProjects();

        assertThat(actual).extracting("name").containsExactly("Sitionix");
        verify(this.httpClient).listProjects();
    }

    @Test
    void rejectsNullListFromUpstream() {
        final var adapter = this.adapter();
        when(this.httpClient.listProjectAgents(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> adapter.listProjectAgents(PROJECT_ID))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("agents");
    }

    @Test
    void mapsCreateAgentRequestAndResponse() throws Exception {
        final var adapter = this.adapter();
        when(this.httpClient.createAgent(any(), any(AgentDefinitionRequest.class))).thenReturn(new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
        ));

        final var actual = adapter.createAgent(PROJECT_ID, new SaveAgentDefinitionCommand(
                "Backend Implementer",
                "Do backend work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of()
        ));

        assertThat(actual.id()).isEqualTo(AGENT_ID);
        verify(this.httpClient).createAgent(any(), any(AgentDefinitionRequest.class));
    }

    @Test
    void mapsCreateProjectRequestAndResponse() {
        final var adapter = this.adapter();
        when(this.httpClient.createProject(any(AgentProjectRequest.class))).thenReturn(new AgentProjectResponse(
                PROJECT_ID,
                "Sitionix",
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
        ));

        final var actual = adapter.createProject(new CreateAgentProjectCommand("Sitionix"));

        assertThat(actual.name()).isEqualTo("Sitionix");
        verify(this.httpClient).createProject(any(AgentProjectRequest.class));
    }

    private ForgeAgentClientAdapter adapter() {
        return new ForgeAgentClientAdapter(
                this.httpClient,
                new ForgeAgentClientMapper(this.objectMapper),
                new ForgeAgentClientCallExecutor(this.properties())
        );
    }

    private ForgeAgentClientProperties properties() {
        final var properties = new ForgeAgentClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("http://forge-agent.test"));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
