package com.sitionix.forgeai.api.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProxyApiMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProxyApiMapper mapper = new AgentProxyApiMapper(this.objectMapper);

    @Test
    void mapsAgentRequestToCommand() throws Exception {
        final var command = this.mapper.toCommand(new AgentDefinitionRequest(
                "Backend",
                "Do work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(AGENT_ID)
        ));

        assertThat(command.name()).isEqualTo("Backend");
        assertThat(command.outputSchema().jsonObject()).isEqualTo("{\"type\":\"object\"}");
        assertThat(command.dependsOnAgentIds()).containsExactly(AGENT_ID);
    }

    @Test
    void rejectsNonObjectOutputSchemaAsLocalInvalidRequest() throws Exception {
        final var request = new AgentDefinitionRequest("Backend", "Do work.", this.objectMapper.readTree("[]"), List.of());

        assertThatThrownBy(() -> this.mapper.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void mapsAgentDetailsToTypedResponse() {
        final var response = this.mapper.toResponse(new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                List.of(),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z")
        ));

        assertThat(response.id()).isEqualTo(AGENT_ID);
        assertThat(response.outputSchema().isObject()).isTrue();
    }
}
