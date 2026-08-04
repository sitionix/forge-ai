package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDependencyResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConversionException;

class ForgeAgentClientMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DEPENDENCY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAgentClientMapper mapper = new ForgeAgentClientMapper(this.objectMapper);

    @Test
    void validAgentResponseMapsSuccessfully() throws Exception {
        final var actual = this.mapper.toDomain(new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(new AgentDependencyResponse(DEPENDENCY_ID, "Architect")),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
        ));

        assertThat(actual.id()).isEqualTo(AGENT_ID);
        assertThat(actual.outputSchema().jsonObject()).isEqualTo("{\"type\":\"object\"}");
        assertThat(actual.dependsOn()).extracting("name").containsExactly("Architect");
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
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
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
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
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
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
        );
    }
}
