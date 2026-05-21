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
class CompleteImplementBeLaneScopeMismatchIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should fail implement_be completion callback on scope mismatch")
    void givenImplementBeScopeMismatch_whenCompleteImplementBeLane_thenReturnBadRequest() {
        //given
        final UUID ticketId = UUID.fromString("61111111-1111-1111-1111-111111111111");
        final UUID implementBeLaneId = UUID.fromString("62222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementBeLaneScopeMismatchSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementBeLaneScopeMismatch())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.error").value("scope_mismatch"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message").value("Implement-be scope mismatch: laneId=62222222-2222-2222-2222-222222222222, laneScope=automationservice-sox, requestScope=backendforfrontendservice-sox"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);
    }
}
