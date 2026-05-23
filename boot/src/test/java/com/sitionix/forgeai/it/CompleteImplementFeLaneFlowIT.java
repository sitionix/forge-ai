package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
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
class CompleteImplementFeLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should complete implement_fe lane")
    void givenCompleteImplementFePayload_whenCompleteImplementFeLane_thenCompleteLane() {
        //given
        final UUID ticketId = UUID.fromString("a1111111-1111-1111-1111-111111111111");
        final UUID laneId = UUID.fromString("a2222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementFeLaneSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementFeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", laneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(laneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> {
                    final boolean implementFeCompleted = value.getLanes().stream()
                            .anyMatch(lane -> Objects.equals(lane.getId(), laneId)
                                    && Objects.equals(LaneStatus.COMPLETED, lane.getStatus()));
                    return implementFeCompleted;
                });
    }
}
