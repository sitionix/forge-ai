package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
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

@IntegrationTest(properties = {
        "spring.task.scheduling.enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
class ReadyToStartImplementBeLaneJobIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Test
    @DisplayName("Should execute ready implement_be lane by scheduler job")
    void givenReadyImplementBeLane_whenSchedulerRuns_thenSubmitImplementBeInputAndMoveLaneInProgress() {
        //given
        final UUID ticketId = UUID.fromString("51111111-1111-1111-1111-111111111111");
        final UUID implementBeLaneId = UUID.fromString("52222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementBeLaneSeedTicket.json");

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementBeLane())
                .withRequest("requestCompleteImplementBeLane.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(implementBeLaneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1);
        this.testManager.mongo()
                .get(AgentTicketDocument.class)
                .hasSize(2);

        //when
        this.readyToStartLaneJob.run();

        //then
        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "attempt", "inputTaskIds")
                .containsWithJsonsStrict("expectedReadyToStartImplementBeLaneJobTicket.json");
    }
}
