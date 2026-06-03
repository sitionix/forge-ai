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
class CompleteImplementBeLaneScopeMismatchIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should fail implement_be completion callback on scope mismatch")
    void givenImplementBeScopeMismatch_whenCompleteImplementBeLane_thenReturnBadRequest() {
        //given
        final UUID ticketId = UUID.fromString("61111111-1111-1111-1111-111111111111");
        final UUID implementBeLaneId = UUID.fromString("62222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementBeLaneScopeMismatchSeedTicket.json");

        //when then
        assertThatThrownBy(() -> this.laneCompletion.completeImplementBeLane(ticketId, implementBeLaneId,
                request -> request.setScope("backendforfrontendservice-sox")))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessage("implement_be scope mismatch: laneId=62222222-2222-2222-2222-222222222222, laneScope=automationservice-sox, requestScope=backendforfrontendservice-sox");

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);
    }
}
