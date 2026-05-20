package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
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
class ArchitectGlobalNotNeededLifecycleIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should mark API and EVENT as NOT_NEEDED when architect marks both as not required")
    void givenApiAndEventNotRequired_whenCompleteArchitect_thenMarkGlobalLanesAsNotNeeded() {
        //given
        final UUID ticketId = UUID.fromString("14141414-1414-1414-1414-141414141414");
        final UUID architectLaneId = UUID.fromString("bbbbbbbb-eeee-eeee-eeee-eeeeeeeeeeee");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("architectGlobalNotNeededSeedTicket.json");

        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneBffApiEventNotRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(architectLaneId.toString()))
                .assertDefault();

        //then
        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("lanes.inputTaskIds", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedArchitectGlobalNotNeededTicket.json");
    }
}
