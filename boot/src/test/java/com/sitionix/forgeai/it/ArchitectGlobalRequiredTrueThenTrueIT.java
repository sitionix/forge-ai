package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class ArchitectGlobalRequiredTrueThenTrueIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
    @Test
    @DisplayName("Should collect two global API and EVENT tasks when both architects set required true")
    void givenApiAndEventRequiredBothTimes_whenCompleteBothArchitects_thenGlobalLanesHaveTwoInputTasks() {
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
        this.laneCompletion.completeArchitectLane(ticketId, firstArchitectLaneId, "requestCompleteArchitectLaneAutomationApiEventRequired.json", request -> { });

        this.laneCompletion.completeArchitectLane(ticketId, secondArchitectLaneId, "requestCompleteArchitectLaneBffApiEventRequired.json", request -> { });

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(6);

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> {
                    final Set<UUID> apiInputTaskIds = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), apiLaneId))
                            .findFirst()
                            .orElseThrow()
                            .getInputTaskIds();
                    final Set<UUID> eventInputTaskIds = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), eventLaneId))
                            .findFirst()
                            .orElseThrow()
                            .getInputTaskIds();
                    return Objects.nonNull(apiInputTaskIds)
                            && Objects.equals(apiInputTaskIds.size(), 2)
                            && Objects.nonNull(eventInputTaskIds)
                            && Objects.equals(eventInputTaskIds.size(), 2);
                });
    }
}
