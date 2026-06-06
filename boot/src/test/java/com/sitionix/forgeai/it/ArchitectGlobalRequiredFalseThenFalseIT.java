package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class ArchitectGlobalRequiredFalseThenFalseIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should mark global API and EVENT lanes as NOT_NEEDED when both architects set required false")
    void givenApiAndEventNotRequiredBothTimes_whenCompleteBothArchitects_thenGlobalLanesAreNotNeededWithoutInputTasks() {
        //given
        final UUID ticketId = UUID.fromString("12121212-1212-1212-1212-121212121212");
        final UUID firstArchitectLaneId = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
        final UUID secondArchitectLaneId = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");
        final UUID apiLaneId = UUID.fromString("eeeeeeee-5555-5555-5555-555555555555");
        final UUID eventLaneId = UUID.fromString("ffffffff-6666-6666-6666-666666666666");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("architectGlobalWaitingSeedTicket.json");

        //when
        this.laneCompletion.completeArchitectLane(ticketId, firstArchitectLaneId, "requestCompleteArchitectLaneAutomationApiEventNotRequired.json", request -> { });

        this.laneCompletion.completeArchitectLane(ticketId, secondArchitectLaneId, "requestCompleteArchitectLaneBffApiEventNotRequired.json", request -> { });

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(2);

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .filter(lane -> Objects.equals(lane.getId(), apiLaneId) || Objects.equals(lane.getId(), eventLaneId))
                        .allMatch(lane -> Objects.equals(LaneStatus.NOT_NEEDED, lane.getStatus())
                                && (Objects.isNull(lane.getInputTaskIds()) || lane.getInputTaskIds().isEmpty())));
    }
}
