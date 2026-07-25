package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteAnalyzerLaneDebugIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should complete analyzer lane and prepare produced lanes")
    void givenTicketWithAnalyzerAndProducedLanes_whenCompleteAnalyzerLane_thenCreateProducedTasksAndUpdateLaneLifecycle() {
        //given
        final UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final UUID analyzerLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeAnalyzerLaneSeedTicket.json");

        //when then
        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerLaneId);

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("lanes.inputTaskIds", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedCompleteAnalyzerLaneTicket.json");

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsAllWithJsons(
                        "expectedArchitectAgentTicket.json",
                        "expectedQaLeadAgentTicket.json"
                );
    }
}
