package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.UUID;

@IntegrationTest(properties = {
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000"
})
class ArchitectLaneDependencyResolutionIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Test
    @DisplayName("Should move architect and qa_lead lanes to READY_TO_START when analyzer dependency is completed")
    void givenArchitectReadyLaneWithAnalyzerDependency_whenJobRuns_thenArchitectExecutionStarts() {
        //given
        final UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final UUID analyzerLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeAnalyzerLaneSeedTicket.json");

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(analyzerLaneId.toString()))
                .assertDefault();

        //then
        final TicketDocument ticket = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        final boolean readyArchitect = ticket.getLanes().stream()
                .anyMatch(lane -> lane.getType() == Agent.ARCHITECT && lane.getStatus() == LaneStatus.READY_TO_START);
        final boolean readyQaLead = ticket.getLanes().stream()
                .anyMatch(lane -> lane.getType() == Agent.QA_LEAD && lane.getStatus() == LaneStatus.READY_TO_START);
        if (!readyArchitect || !readyQaLead) {
            throw new AssertionError("Expected ARCHITECT and QA_LEAD lanes to be READY_TO_START after analyzer completion.");
        }
    }
}
