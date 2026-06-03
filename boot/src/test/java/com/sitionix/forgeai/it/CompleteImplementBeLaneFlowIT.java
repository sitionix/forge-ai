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
class CompleteImplementBeLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should create test_unit and test_it tasks when implement_be callback has changed files and integration changes")
    void givenImplementBeCompletePayload_whenCompleteImplementBeLane_thenCreateTestUnitAndTestItTasks() {
        //given
        final UUID ticketId = UUID.fromString("51111111-1111-1111-1111-111111111111");
        final UUID implementBeLaneId = UUID.fromString("52222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementBeLaneSeedTicket.json");

        //when then
        this.laneCompletion.completeImplementBeLane(ticketId, implementBeLaneId);

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsAllWithJsons(
                        "expectedImplementBeCompleteTestUnitTicket.json",
                        "expectedImplementBeCompleteTestItTicket.json"
                );

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), implementBeLaneId)
                                && Objects.equals(LaneStatus.COMPLETED, lane.getStatus()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("53333333-3333-3333-3333-333333333333"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("54444444-4444-4444-4444-444444444444"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("55555555-5555-5555-5555-555555555555"))
                                && Objects.equals(LaneStatus.COMPLETED, lane.getStatus())));
    }
}
