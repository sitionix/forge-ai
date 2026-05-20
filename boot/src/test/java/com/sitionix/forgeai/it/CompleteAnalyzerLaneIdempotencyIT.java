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
class CompleteAnalyzerLaneIdempotencyIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should ignore repeated analyzer completion callback for already completed lane")
    void givenAnalyzerLaneCompleted_whenSubmitCompleteAnalyzerSecondTime_thenDoNotCreateDuplicateArchitectAndQaLeadTasks() {
        //given
        final UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final UUID analyzerLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        final UUID architectLaneId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        final UUID qaLeadLaneId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeAnalyzerLaneSeedTicket.json");

        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(analyzerLaneId.toString()))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(analyzerLaneId.toString()))
                .assertDefault();

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(2);

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), analyzerLaneId)
                                && Objects.equals("COMPLETED", lane.getStatus().name()))
                        && value.getLanes().stream()
                        .filter(lane -> Objects.equals(lane.getId(), architectLaneId) || Objects.equals(lane.getId(), qaLeadLaneId))
                        .allMatch(lane -> Objects.equals("READY_TO_START", lane.getStatus().name())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
    }
}
