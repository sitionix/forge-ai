package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.List;
import java.util.Objects;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=100")
class ReadyToStartLaneJobIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Test
    @DisplayName("Should move analyzer lanes to in progress by scheduler job")
    void givenStartForgeRequest_whenSchedulerRuns_thenMoveAnalyzerLanesToInProgress() throws Exception {
        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticket").value("SITIONIX-1"))
                .assertDefault();

        //then
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                final TicketDocument actual = this.testManager.mongo()
                        .get(TicketDocument.class)
                        .hasSize(1)
                        .singleElement()
                        .assertEntity();
                final List<LaneDocument> lanes = actual.getLanes();
                final boolean valid = lanes.stream()
                        .filter(lane -> Objects.equals("ANALYZER", lane.getType().name()))
                        .allMatch(lane ->
                                Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                        || Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus()));
                if (!valid) {
                    throw new AssertionError("Analyzer lanes are not in READY_TO_START/IN_PROGRESS state");
                }
                return;
            } catch (AssertionError error) {
                Thread.sleep(200);
            }
        }

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        final List<LaneDocument> lanes = actual.getLanes();
        final boolean valid = lanes.stream()
                .filter(lane -> Objects.equals("ANALYZER", lane.getType().name()))
                .allMatch(lane ->
                        Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                || Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus()));
        if (!valid) {
            throw new AssertionError("Analyzer lanes are not in READY_TO_START/IN_PROGRESS state");
        }
    }
}
