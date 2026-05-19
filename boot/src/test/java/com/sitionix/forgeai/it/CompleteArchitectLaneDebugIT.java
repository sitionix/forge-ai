package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteArchitectLaneDebugIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should complete architect lane and prepare produced lanes")
    void givenTicketWithArchitectAndProducedLanes_whenCompleteArchitectLane_thenCreateProducedTasksAndUpdateLaneLifecycle() {
        //given
        final UUID ticketId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        final UUID architectLaneId = UUID.fromString("66666666-6666-6666-6666-666666666666");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeArchitectLaneSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(architectLaneId.toString()))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("lanes.inputTaskIds", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedCompleteArchitectLaneTicket.json");

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(3)
                .containsAllWithJsons(
                        "expectedImplementBeAgentTicket.json",
                        "expectedApiAgentTicket.json",
                        "expectedEventAgentTicket.json"
                );
    }
}
