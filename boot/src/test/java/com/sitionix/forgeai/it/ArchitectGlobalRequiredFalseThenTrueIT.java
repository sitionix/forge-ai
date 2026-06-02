package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class ArchitectGlobalRequiredFalseThenTrueIT {

    @Autowired
    private TestManager testManager;
    @Test
    @DisplayName("Should keep one global API and EVENT task when required false then true")
    void givenApiAndEventNotRequiredThenRequired_whenCompleteBothArchitects_thenGlobalLanesHaveSingleInputTask() {
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
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneAutomationApiEventNotRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", firstArchitectLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneBffApiEventRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", secondArchitectLaneId))
                .assertDefault();

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(4);

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .filter(lane -> Objects.equals(lane.getId(), apiLaneId) || Objects.equals(lane.getId(), eventLaneId))
                        .allMatch(lane -> Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
    }
}
