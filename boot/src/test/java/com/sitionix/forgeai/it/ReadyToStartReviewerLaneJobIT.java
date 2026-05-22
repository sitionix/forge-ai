package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.mock.mockito.MockBean;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReadyToStartReviewerLaneJobIT {

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
    @DisplayName("Should execute reviewer lane by scheduler job")
    void givenReadyReviewerLane_whenSchedulerRuns_thenExecuteReviewerLane() {
        //given
        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("readyToStartReviewerJobSeedTicket.json");

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1);

        //when
        this.readyToStartLaneJob.run();

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "attempt", "inputTaskIds")
                .containsWithJsonsStrict("expectedReadyToStartReviewerLaneJobTicket.json");
    }
}
