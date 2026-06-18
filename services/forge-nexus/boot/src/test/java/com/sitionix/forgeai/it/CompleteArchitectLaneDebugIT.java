package com.sitionix.forgeai.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.AgentTicketJpaRepository;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteArchitectLaneDebugIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Autowired
    private AgentTicketJpaRepository agentTicketJpaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should complete architect lane and prepare produced lanes")
    void givenTicketWithArchitectAndProducedLanes_whenCompleteArchitectLane_thenCreateProducedTasksAndUpdateLaneLifecycle() throws IOException {
        //given
        final UUID ticketId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        final UUID architectLaneId = UUID.fromString("66666666-6666-6666-6666-666666666666");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeArchitectLaneSeedTicket.json");

        //when then
        this.laneCompletion.completeArchitectLane(ticketId, architectLaneId);

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("lanes.inputTaskIds", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedCompleteArchitectLaneTicket.json");

        assertThat(this.agentTicketJpaRepository.findAll().stream()
                .map(this::comparableAgentTicket)
                .toList())
                .containsExactlyInAnyOrderElementsOf(List.of(
                        this.expectedAgentTicket("expectedImplementBeAgentTicket.json"),
                        this.expectedAgentTicket("expectedApiAgentTicket.json"),
                        this.expectedAgentTicket("expectedEventAgentTicket.json")
                ));
    }

    private Map<String, Object> comparableAgentTicket(final AgentTicketDocument document) {
        final Map<String, Object> result = this.objectMapper.convertValue(document, new TypeReference<>() {
        });
        Set.of("id", "ticketId", "laneId", "createdAt", "updatedAt").forEach(result::remove);
        return result;
    }

    private Map<String, Object> expectedAgentTicket(final String fileName) throws IOException {
        try (InputStream inputStream = new ClassPathResource("forge-it/db/mongodb/entities/expected/" + fileName).getInputStream()) {
            return this.objectMapper.readValue(inputStream, new TypeReference<>() {
            });
        }
    }
}
