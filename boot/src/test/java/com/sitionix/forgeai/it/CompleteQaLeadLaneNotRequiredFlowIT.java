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
class CompleteQaLeadLaneNotRequiredFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should mark test_unit and test_it as not needed when QA lead marks backend tests as optional")
    void givenBackendQaLeadNotRequiredPayload_whenCompleteQaLeadLane_thenMarkBackendTestLanesNotNeeded() {
        //given
        final UUID ticketId = UUID.fromString("81111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("82222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneBackendNotRequiredSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackendNotRequired())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(qaLeadLaneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("createdAt", "updatedAt", "attempt", "inputTaskIds")
                .hasSize(1)
                .containsWithJsonsStrict("expectedQaLeadNotRequiredTicket.json");
    }
}
