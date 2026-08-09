package com.sitionix.forgeai.infrastructure.agentclient;

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
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDependencyResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConversionException;

class ForgeAgentClientMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DEPENDENCY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED = Instant.parse("2026-08-04T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-04T00:01:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAgentClientMapper mapper = new ForgeAgentClientMapper(this.objectMapper);

    @Test
    void projectCommandMapsToRequest() {
        final var command = new CreateAgentProjectCommand("Sitionix");
        final var expected = new AgentProjectRequest("Sitionix");

        final var actual = this.mapper.toRequest(command);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void agentCommandMapsToRequest() throws Exception {
        final var command = new SaveAgentDefinitionCommand(
                "Backend Implementer",
                "Do backend work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of(DEPENDENCY_ID)
        );
        final var expected = new AgentDefinitionRequest(
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(DEPENDENCY_ID)
        );

        final var actual = this.mapper.toRequest(command);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void validProjectResponseMapsSuccessfully() {
        final var response = new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED);
        final var expected = new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED);

        final var actual = this.mapper.toDomain(response);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void validAgentListResponseMapsSuccessfully() {
        final var response = new AgentDefinitionListResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                List.of(new AgentDependencyResponse(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );
        final var expected = new AgentDefinitionListItem(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                List.of(new AgentDependencySummary(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );

        final var actual = this.mapper.toDomain(response);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void validAgentResponseMapsSuccessfully() throws Exception {
        final var response = new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(new AgentDependencyResponse(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );
        final var expected = new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of(new AgentDependencySummary(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );

        final var actual = this.mapper.toDomain(response);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void nullOutputSchemaFailsClosed() {
        assertThatThrownBy(() -> this.mapper.toDomain(this.responseWithOutputSchema(null)))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("outputSchema");
    }

    @Test
    void arrayOutputSchemaFailsClosed() throws Exception {
        assertThatThrownBy(() -> this.mapper.toDomain(this.responseWithOutputSchema(this.objectMapper.readTree("[]"))))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("outputSchema");
    }

    @Test
    void nullUpstreamResponseFailsClosed() {
        assertThatThrownBy(() -> this.mapper.toDomain((AgentDefinitionResponse) null))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void invalidDependencyEntryFailsClosed() throws Exception {
        assertThatThrownBy(() -> this.mapper.toDomain(new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{}"),
                List.of(new AgentDependencyResponse(null, " ")),
                CREATED,
                UPDATED
        )))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("dependency.id");
    }

    @Test
    void nullDependenciesFailsClosed() throws Exception {
        assertThatThrownBy(() -> this.mapper.toDomain(new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{}"),
                null,
                CREATED,
                UPDATED
        )))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("dependsOn");
    }

    @Test
    void nullListFailsClosed() {
        assertThatThrownBy(() -> this.mapper.requireList(null, "agents"))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("agents");
    }

    private AgentDefinitionResponse responseWithOutputSchema(final com.fasterxml.jackson.databind.JsonNode outputSchema) {
        return new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                outputSchema,
                List.of(),
                CREATED,
                UPDATED
        );
    }
}
