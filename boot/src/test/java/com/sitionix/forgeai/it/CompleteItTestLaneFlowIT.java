package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteItTestLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should store integration test report and complete test_it lane")
    void givenCompleteItTestPayload_whenCompleteItTestLane_thenStoreReportAndCompleteLane() {
        //given
        final UUID ticketId = UUID.fromString("81111111-1111-1111-1111-111111111111");
        final UUID laneId = UUID.fromString("82222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeItTestLaneSeedTicket.json");

        final AgentTicketDocument agentTicketDocument = new AgentTicketDocument();
        agentTicketDocument.setId(UUID.fromString("94444444-4444-4444-4444-444444444444"));
        agentTicketDocument.setTicketId(ticketId);
        agentTicketDocument.setLaneId(laneId);
        agentTicketDocument.setStatus(AgentTicketStatus.CREATED);
        agentTicketDocument.setScope("automationservice-sox");
        agentTicketDocument.setAgent(Agent.TEST_IT);
        agentTicketDocument.setPayload(this.getSeedTestItPayload());
        agentTicketDocument.setCreatedAt(LocalDateTime.parse("2026-01-01T10:00:00"));
        agentTicketDocument.setUpdatedAt(LocalDateTime.parse("2026-01-01T10:00:00"));
        this.testManager.mongo()
                .create(AgentTicketDocument.class)
                .body(agentTicketDocument);

        //when then
        this.laneCompletion.completeItTestLane(ticketId, laneId);

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsAllWithJsons(
                        "completeItTestLaneSeedAgentTicket.json",
                        "expectedCompleteItTestLaneReportTicket.json"
                );

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "attempt", "inputTaskIds")
                .hasSize(1)
                .containsWithJsonsStrict("expectedCompleteItTestLaneTicket.json");
    }

    private TestItPayload getSeedTestItPayload() {
        return new TestItPayload(
                "Prepare integration test execution context",
                "automationservice-sox",
                "Prepared integration test cases for backend agent action implementation.",
                Set.of(),
                Set.of(),
                null,
                Set.of(),
                Set.of()
        );
    }
}
