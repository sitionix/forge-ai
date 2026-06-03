package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteQaLeadLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should create test_it task and complete qa_lead lane for backend scope")
    void givenBackendQaLeadCompletePayload_whenCompleteQaLeadLane_thenCreateTestItTask() {
        //given
        final UUID ticketId = UUID.fromString("71111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("72222222-2222-2222-2222-222222222222");
        final UUID testUnitLaneId = UUID.fromString("76666666-6666-6666-6666-666666666666");
        final UUID testItLaneId = UUID.fromString("73333333-3333-3333-3333-333333333333");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneBackendSeedTicket.json");

        //when then
        this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadLaneId);

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsWithJsonsStrict("expectedQaLeadCompleteTestItTicket.json", "expectedQaLeadCompleteTestUnitTicket.json");

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("createdAt", "updatedAt", "attempt", "inputTaskIds")
                .hasSize(1)
                .containsWithJsonsStrict("expectedQaLeadCompleteTicket.json");
    }

}
