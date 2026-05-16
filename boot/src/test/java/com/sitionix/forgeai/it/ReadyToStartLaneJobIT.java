package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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
                this.testManager.mongo()
                        .assertEntities(TicketDocument.class)
                        .ignoreFields("id", "createdAt", "updatedAt", "lanes.id", "lanes.inputTaskId")
                        .hasSize(1)
                        .containsAllWithJsons("expectedReadyToStartJobTicket.json");
                return;
            } catch (AssertionError error) {
                Thread.sleep(200);
            }
        }

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "lanes.id", "lanes.inputTaskId")
                .hasSize(1)
                .containsAllWithJsons("expectedReadyToStartJobTicket.json");
    }
}
