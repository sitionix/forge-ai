package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteQaLeadLaneNotRequiredFlowIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should mark test_unit and test_it as not needed when QA lead marks backend tests as optional")
    void givenBackendQaLeadNotRequiredPayload_whenCompleteQaLeadLane_thenMarkBackendTestLanesNotNeeded() {
        //given
        final UUID ticketId = UUID.fromString("81111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("82222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneBackendNotRequiredSeedTicket.json");

        //when then
        this.laneCompletion.completeQaLeadLaneBackendNotRequired(ticketId, qaLeadLaneId);

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("createdAt", "updatedAt", "attempt", "inputTaskIds")
                .hasSize(1)
                .containsWithJsonsStrict("expectedQaLeadNotRequiredTicket.json");
    }
}
