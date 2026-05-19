package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
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
class CompleteApiLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should create one implement_be task when one backend scope is produced by API lane")
    void givenOneBackendProducedLane_whenCompleteApiLane_thenCreateOneImplementBeTask() {
        //given
        final UUID ticketId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneOneBeSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(apiLaneId.toString()))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedApiCompleteImplementBeAutomationTicket.json");

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> {
                    final LaneDocument apiLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), apiLaneId))
                            .findFirst()
                            .orElseThrow();
                    final LaneDocument implementBeLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), UUID.fromString("23333333-3333-3333-3333-333333333333")))
                            .findFirst()
                            .orElseThrow();
                    return Objects.equals(apiLane.getStatus().name(), "COMPLETED")
                            && Objects.equals(implementBeLane.getStatus().name(), "READY_TO_START")
                            && Objects.nonNull(implementBeLane.getInputTaskIds())
                            && Objects.equals(implementBeLane.getInputTaskIds().size(), 1);
                });
    }

    @Test
    @DisplayName("Should create two implement_be tasks when two backend scopes are produced by API lane")
    void givenTwoBackendProducedLanes_whenCompleteApiLane_thenCreateTwoImplementBeTasks() {
        //given
        final UUID ticketId = UUID.fromString("31111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("32222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneTwoBeSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(apiLaneId.toString()))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsAllWithJsons(
                        "expectedApiCompleteImplementBeAutomationTicket.json",
                        "expectedApiCompleteImplementBeBffTicket.json"
                );

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> {
                    final LaneDocument apiLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), apiLaneId))
                            .findFirst()
                            .orElseThrow();
                    final LaneDocument implementBeAutomationLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), UUID.fromString("33333333-3333-3333-3333-333333333333")))
                            .findFirst()
                            .orElseThrow();
                    final LaneDocument implementBeBffLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), UUID.fromString("34444444-4444-4444-4444-444444444444")))
                            .findFirst()
                            .orElseThrow();
                    return Objects.equals(apiLane.getStatus().name(), "COMPLETED")
                            && Objects.equals(implementBeAutomationLane.getStatus().name(), "READY_TO_START")
                            && Objects.equals(implementBeBffLane.getStatus().name(), "READY_TO_START")
                            && Objects.nonNull(implementBeAutomationLane.getInputTaskIds())
                            && Objects.equals(implementBeAutomationLane.getInputTaskIds().size(), 1)
                            && Objects.nonNull(implementBeBffLane.getInputTaskIds())
                            && Objects.equals(implementBeBffLane.getInputTaskIds().size(), 1);
                });
    }

    @Test
    @DisplayName("Should create two implement_be and one implement_fe tasks when API lane produces two backend and one frontend scopes")
    void givenTwoBackendAndOneFrontendProducedLanes_whenCompleteApiLane_thenCreateThreeImplementationTasks() {
        //given
        final UUID ticketId = UUID.fromString("41111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("42222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneTwoBeOneFeSeedTicket.json");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(apiLaneId.toString()))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(3)
                .containsAllWithJsons(
                        "expectedApiCompleteImplementBeAutomationTicket.json",
                        "expectedApiCompleteImplementBeBffTicket.json",
                        "expectedApiCompleteImplementFeSpaTicket.json"
                );

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> {
                    final LaneDocument apiLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), apiLaneId))
                            .findFirst()
                            .orElseThrow();
                    final LaneDocument implementBeAutomationLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), UUID.fromString("43333333-3333-3333-3333-333333333333")))
                            .findFirst()
                            .orElseThrow();
                    final LaneDocument implementBeBffLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), UUID.fromString("44444444-4444-4444-4444-444444444444")))
                            .findFirst()
                            .orElseThrow();
                    final LaneDocument implementFeLane = value.getLanes().stream()
                            .filter(lane -> Objects.equals(lane.getId(), UUID.fromString("45555555-5555-5555-5555-555555555555")))
                            .findFirst()
                            .orElseThrow();
                    return Objects.equals(apiLane.getStatus().name(), "COMPLETED")
                            && Objects.equals(implementBeAutomationLane.getStatus().name(), "READY_TO_START")
                            && Objects.equals(implementBeBffLane.getStatus().name(), "READY_TO_START")
                            && Objects.equals(implementFeLane.getStatus().name(), "READY_TO_START")
                            && Objects.nonNull(implementBeAutomationLane.getInputTaskIds())
                            && Objects.equals(implementBeAutomationLane.getInputTaskIds().size(), 1)
                            && Objects.nonNull(implementBeBffLane.getInputTaskIds())
                            && Objects.equals(implementBeBffLane.getInputTaskIds().size(), 1)
                            && Objects.nonNull(implementFeLane.getInputTaskIds())
                            && Objects.equals(implementFeLane.getInputTaskIds().size(), 1);
                });
    }
}
