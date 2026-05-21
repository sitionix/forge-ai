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
class CompleteImplementBeLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should create test_unit and test_it tasks when implement_be callback has changed files and integration changes")
    void givenImplementBeCompletePayload_whenCompleteImplementBeLane_thenCreateTestUnitAndTestItTasks() {
        //given
        final UUID ticketId = UUID.fromString("51111111-1111-1111-1111-111111111111");
        final UUID implementBeLaneId = UUID.fromString("52222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementBeLaneSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementBeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(implementBeLaneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsAllWithJsons(
                        "expectedImplementBeCompleteTestUnitTicket.json",
                        "expectedImplementBeCompleteTestItTicket.json"
                );

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), implementBeLaneId)
                                && Objects.equals("COMPLETED", lane.getStatus().name()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("53333333-3333-3333-3333-333333333333"))
                                && Objects.equals("READY_TO_START", lane.getStatus().name())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("54444444-4444-4444-4444-444444444444"))
                                && Objects.equals("READY_TO_START", lane.getStatus().name())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("55555555-5555-5555-5555-555555555555"))
                                && Objects.equals("COMPLETED", lane.getStatus().name())));
    }
}
