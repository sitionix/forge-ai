package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class ArchitectGlobalLaneLifecycleIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should keep API and EVENT NOT_STARTED when one of architect dependencies is still IN_PROGRESS")
    void givenTwoArchitectDependenciesAndSecondArchitectInProgress_whenCompleteFirstArchitect_thenApiAndEventStayNotStarted() {
        //given
        final UUID ticketId = UUID.fromString("12121212-1212-1212-1212-121212121212");
        final UUID architectLaneId = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("eeeeeeee-5555-5555-5555-555555555555");
        final UUID eventLaneId = UUID.fromString("ffffffff-6666-6666-6666-666666666666");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("architectGlobalWaitingSeedTicket.json");

        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneAutomationApiEventRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(architectLaneId.toString()))
                .assertDefault();

        //then
        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .filter(lane -> Objects.equals(lane.getId(), apiLaneId) || Objects.equals(lane.getId(), eventLaneId))
                        .allMatch(lane -> Objects.equals("NOT_STARTED", lane.getStatus().name())));
    }

    @Test
    @DisplayName("Should move API and EVENT to READY_TO_START when remaining architect dependency is completed")
    void givenOneArchitectAlreadyCompleted_whenCompleteSecondArchitect_thenApiAndEventBecomeReadyToStart() {
        //given
        final UUID ticketId = UUID.fromString("13131313-1313-1313-1313-131313131313");
        final UUID architectLaneId = UUID.fromString("bbbbbbbb-8888-8888-8888-888888888888");
        final UUID apiLaneId = UUID.fromString("eeeeeeee-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final UUID eventLaneId = UUID.fromString("ffffffff-cccc-cccc-cccc-cccccccccccc");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("architectGlobalReadySeedTicket.json");

        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneBffApiEventRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(architectLaneId.toString()))
                .assertDefault();

        //then
        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .filter(lane -> Objects.equals(lane.getId(), apiLaneId) || Objects.equals(lane.getId(), eventLaneId))
                        .allMatch(lane -> Objects.equals("READY_TO_START", lane.getStatus().name())));
    }
}
