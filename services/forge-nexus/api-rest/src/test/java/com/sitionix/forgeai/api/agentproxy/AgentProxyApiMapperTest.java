package com.sitionix.forgeai.api.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDependencySummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProxyApiMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DEPENDENCY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED = Instant.parse("2026-08-04T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-04T00:01:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProxyApiMapper mapper = new AgentProxyApiMapper(this.objectMapper);

    @Test
    void mapsProjectRequestToCommand() {
        final var request = new AgentProjectRequest("Sitionix");
        final var expected = new CreateAgentProjectCommand("Sitionix");

        final var actual = this.mapper.toCommand(request);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsAgentRequestToCommand() throws Exception {
        final var request = new AgentDefinitionRequest(
                "Backend",
                "Do work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(AGENT_ID)
        );
        final var expected = new SaveAgentDefinitionCommand(
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of(AGENT_ID)
        );

        final var actual = this.mapper.toCommand(request);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void rejectsNonObjectOutputSchemaAsLocalInvalidRequest() throws Exception {
        final var request = new AgentDefinitionRequest("Backend", "Do work.", this.objectMapper.readTree("[]"), List.of());

        assertThatThrownBy(() -> this.mapper.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void mapsProjectToTypedResponse() {
        final var project = new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        final var expected = new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED);

        final var actual = this.mapper.toResponse(project);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsAgentListItemToTypedResponse() {
        final var item = new AgentDefinitionListItem(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                List.of(new AgentDependencySummary(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );
        final var expected = new AgentDefinitionListResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                List.of(new AgentDependencyResponse(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );

        final var actual = this.mapper.toResponse(item);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsAgentDetailsToTypedResponse() throws Exception {
        final var agent = new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of(new AgentDependencySummary(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );
        final var expected = new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(new AgentDependencyResponse(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );

        final var actual = this.mapper.toResponse(agent);

        assertThat(actual).isEqualTo(expected);
    }
}
