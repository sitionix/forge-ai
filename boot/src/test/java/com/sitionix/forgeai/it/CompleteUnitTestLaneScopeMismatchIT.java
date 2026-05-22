package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.domain.port.CodexClient;
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
import org.springframework.http.HttpStatus;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteUnitTestLaneScopeMismatchIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Test
    @DisplayName("Should fail unit test completion when request scope does not match lane scope")
    void givenScopeMismatch_whenCompleteUnitTestLane_thenReturnBadRequest() {
        //given
        final UUID ticketId = UUID.fromString("10111111-1111-1111-1111-111111111111");
        final UUID testUnitLaneId = UUID.fromString("10222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeUnitTestLaneSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeUnitTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testUnitLaneId))
                .withRequest("requestCompleteUnitTestLane.json", request -> request.setScope("backendforfrontendservice-sox"))
                .expectStatus(HttpStatus.BAD_REQUEST)
                .assertAndCreate();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "attempt", "inputTaskIds")
                .containsWithJsonsStrict("expectedCompleteUnitTestLaneSeedTicket.json");
    }
}
