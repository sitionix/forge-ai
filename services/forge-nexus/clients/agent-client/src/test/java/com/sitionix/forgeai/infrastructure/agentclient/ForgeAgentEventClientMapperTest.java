package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentExecutionEventPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentExecutionEventResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForgeAgentEventClientMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ForgeAgentClientMapper mapper = new ForgeAgentClientMapper(this.json);

    @Test
    void mapsProviderNeutralEventPageIncludingLegacyCaptureStatus() throws Exception {
        final UUID turnId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        final AgentExecutionEventResponse event = new AgentExecutionEventResponse(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"), turnId,
                UUID.fromString("44444444-4444-4444-8444-444444444444"), 6,
                "COMMAND", "FAILED", null, "item:cmd:completed",
                this.json.readTree("{\"command\":\"false\",\"exitCode\":1}"),
                Instant.parse("2026-09-07T09:00:00Z"), Instant.parse("2026-09-07T09:00:01Z"));

        final var result = this.mapper.toDomain(new AgentExecutionEventPageResponse(
                turnId, "UNAVAILABLE", List.of(event), 6, 6, false));

        assertThat(result.turnId()).isEqualTo(turnId);
        assertThat(result.captureStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.events()).singleElement().satisfies(mapped -> {
            assertThat(mapped.type()).isEqualTo("COMMAND");
            assertThat(mapped.status()).isEqualTo("FAILED");
            assertThat(mapped.payload()).isEqualTo("{\"command\":\"false\",\"exitCode\":1}");
        });
    }
}
