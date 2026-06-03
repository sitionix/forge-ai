package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class ArchitectGlobalNotNeededLifecycleIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should mark API and EVENT as NOT_NEEDED when architect marks both as not required")
    void givenApiAndEventNotRequired_whenCompleteArchitect_thenMarkGlobalLanesAsNotNeeded() {
        //given
        final UUID ticketId = UUID.fromString("14141414-1414-1414-1414-141414141414");
        final UUID architectLaneId = UUID.fromString("bbbbbbbb-eeee-eeee-eeee-eeeeeeeeeeee");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("architectGlobalNotNeededSeedTicket.json");

        //when
        this.laneCompletion.completeArchitectLane(ticketId, architectLaneId, "requestCompleteArchitectLaneBffApiEventNotRequired.json", request -> { });

        //then
        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("lanes.inputTaskIds", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedArchitectGlobalNotNeededTicket.json");
    }
}
