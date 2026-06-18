package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteUiTestLaneFlowIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should complete test_ui lane without creating downstream tasks")
    void givenCompleteUiTestPayload_whenCompleteUiTestLane_thenCompleteLane() {
        // given
        final UUID ticketId = UUID.fromString("f1111111-1111-1111-1111-111111111111");
        final UUID testUiLaneId = UUID.fromString("f2222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeUiTestLaneSeedTicket.json");

        // when
        this.laneCompletion.completeUiTestLane(ticketId, testUiLaneId);

        // then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(this.laneStatus(actual, testUiLaneId)).isEqualTo(LaneStatus.COMPLETED);
    }

    private LaneStatus laneStatus(final TicketDocument ticket, final UUID laneId) {
        return ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getId(), laneId))
                .findFirst()
                .orElseThrow()
                .getStatus();
    }
}
