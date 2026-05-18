package com.sitionix.forgeai.it;

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

@IntegrationTest
class ForgeAiStartFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should build Codex payload and persist ticket")
    void givenStartForgeRequest_whenStartForge_thenBuildPromptAndPersistTicket() {
        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.id").isNotEmpty())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.createdAt").isNotEmpty())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticket").value("SITIONIX-1"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.task").value("hi"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.scope").value("forge-ai"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OPEN"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "lanes.id", "lanes.inputTaskIds")
                .hasSize(1)
                .containsAllWithJsons("expectedStartForgeTicket.json");
    }

    @Test
    @DisplayName("Should build Codex payload and persist ticket for frontend-only scope")
    void givenStartForgeFrontendRequest_whenStartForge_thenBuildPromptAndPersistTicket() {
        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForgeFrontend())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.id").isNotEmpty())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.createdAt").isNotEmpty())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticket").value("SITIONIX-2"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.task").value("frontend task"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.scope").value("forge-ai"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OPEN"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "lanes.id", "lanes.inputTaskIds")
                .hasSize(1)
                .containsAllWithJsons("expectedStartForgeTicketFrontend.json");
    }
}
