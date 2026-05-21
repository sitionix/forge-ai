package com.sitionix.forgeai.it;

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

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteQaLeadLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should create test_it task and complete qa_lead lane for backend scope")
    void givenBackendQaLeadCompletePayload_whenCompleteQaLeadLane_thenCreateTestItTask() {
        //given
        final UUID ticketId = UUID.fromString("71111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("72222222-2222-2222-2222-222222222222");
        final UUID testItLaneId = UUID.fromString("73333333-3333-3333-3333-333333333333");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneBackendSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(qaLeadLaneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedQaLeadCompleteTestItTicket.json");

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), qaLeadLaneId)
                                && Objects.equals("COMPLETED", lane.getStatus().name()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), testItLaneId)
                                && Objects.equals("READY_TO_START", lane.getStatus().name())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
    }

    @Test
    @DisplayName("Should create test_ui task and complete qa_lead lane for frontend scope")
    void givenFrontendQaLeadCompletePayload_whenCompleteQaLeadLane_thenCreateTestUiTask() {
        //given
        final UUID ticketId = UUID.fromString("81111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("82222222-2222-2222-2222-222222222222");
        final UUID testUiLaneId = UUID.fromString("83333333-3333-3333-3333-333333333333");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneFrontendSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneFrontend())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(qaLeadLaneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedQaLeadCompleteTestUiTicket.json");

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), qaLeadLaneId)
                                && Objects.equals("COMPLETED", lane.getStatus().name()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), testUiLaneId)
                                && Objects.equals("READY_TO_START", lane.getStatus().name())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
    }
}
