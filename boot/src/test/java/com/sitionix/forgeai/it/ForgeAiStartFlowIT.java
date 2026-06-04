package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ForgeAiStartFlowIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;
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

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        assertThat(actual.getLanes()).hasSize(15);
        assertThat(actual.getLanes()).anyMatch(lane -> lane.getType() == Agent.EVENT);
        assertThat(actual.getLanes()).anyMatch(lane -> lane.getType() == Agent.REVIEWER);
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

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        assertThat(actual.getLanes()).hasSize(7);
        assertThat(actual.getLanes()).noneMatch(lane -> lane.getType() == Agent.EVENT);
        assertThat(actual.getLanes()).anyMatch(lane -> lane.getType() == Agent.REVIEWER);
    }
}
