package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteQaLeadLaneFrontendFlowIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should create test_ui task and complete qa_lead lane for frontend scope")
    void givenFrontendQaLeadCompletePayload_whenCompleteQaLeadLane_thenCreateTestUiTask() {
        //given
        final UUID ticketId = UUID.fromString("91111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("92222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneFrontendSeedTicket.json");

        //when then
        this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadLaneId, request -> {
            request.setScope("sitionix-spa");
            request.getTestLaneRequirements().setUnitTestRequired(false);
            request.getTestLaneRequirements().setIntegrationTestRequired(false);
            request.getTestLaneRequirements().setUiTestRequired(true);
            request.setIntegrationTestCases(List.of());
        });

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(1)
                .containsWithJsonsStrict("expectedQaLeadCompleteTestUiTicket.json");

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("createdAt", "updatedAt", "attempt", "inputTaskIds")
                .hasSize(1)
                .containsWithJsonsStrict("expectedQaLeadFrontendCompleteTicket.json");
    }
}
