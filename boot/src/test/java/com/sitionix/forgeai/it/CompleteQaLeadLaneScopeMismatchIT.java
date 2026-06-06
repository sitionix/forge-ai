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
class CompleteQaLeadLaneScopeMismatchIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should fail qa_lead completion on output scope mismatch")
    void givenQaLeadScopeMismatch_whenCompleteQaLeadLane_thenReturnBadRequest() {
        //given
        final UUID ticketId = UUID.fromString("91111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("92222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneScopeMismatchSeedTicket.json");

        //when then
        assertThatThrownBy(() -> this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadLaneId,
                request -> request.setScope("backendforfrontendservice-sox")))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessage("Completion output scope mismatch: sourceLaneId=92222222-2222-2222-2222-222222222222, sourceAgent=qa_lead, targetAgent=test_unit, expectedScope=automationservice-sox, actualScope=backendforfrontendservice-sox");

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);
    }
}
