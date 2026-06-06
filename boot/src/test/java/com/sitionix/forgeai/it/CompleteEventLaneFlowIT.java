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
class CompleteEventLaneFlowIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should complete event lane and make backend implementation ready when dependencies are satisfied")
    void givenCompleteEventPayload_whenCompleteEventLane_thenCompleteLaneAndPrepareBackendImplementation() {
        // given
        final UUID ticketId = UUID.fromString("e1111111-1111-1111-1111-111111111111");
        final UUID eventLaneId = UUID.fromString("e6666666-6666-6666-6666-666666666666");
        final UUID implementBeLaneId = UUID.fromString("e8888888-8888-8888-8888-888888888888");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeEventLaneSeedTicket.json");

        // when
        this.laneCompletion.completeEventLane(ticketId, eventLaneId);

        // then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(this.laneStatus(actual, eventLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.laneStatus(actual, implementBeLaneId)).isEqualTo(LaneStatus.READY_TO_START);
    }

    private LaneStatus laneStatus(final TicketDocument ticket, final UUID laneId) {
        return ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getId(), laneId))
                .findFirst()
                .orElseThrow()
                .getStatus();
    }
}
