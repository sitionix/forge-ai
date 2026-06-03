package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteImplementFeLaneScopeMismatchIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should fail implement_fe completion callback on scope mismatch")
    void givenImplementFeScopeMismatch_whenCompleteImplementFeLane_thenReturnBadRequest() {
        //given
        final UUID ticketId = UUID.fromString("b1111111-1111-1111-1111-111111111111");
        final UUID laneId = UUID.fromString("b2222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementFeLaneScopeMismatchSeedTicket.json");

        //when then
        assertThatThrownBy(() -> this.laneCompletion.completeImplementFeLane(ticketId, laneId,
                request -> request.setScope("automationservice-sox")))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessage("implement_fe scope mismatch: laneId=b2222222-2222-2222-2222-222222222222, laneScope=sitionix-spa, requestScope=automationservice-sox");

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);
    }
}
