package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteApiLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;
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
        this.laneCompletion.completeApiLane(ticketId, apiLaneId);

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(1)
                .containsAllWithJsons("expectedApiCompleteImplementBeAutomationTicket.json");

        this.testManager.mongo()
                .get(AgentTicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> Objects.equals(value.getLaneId(), UUID.fromString("23333333-3333-3333-3333-333333333333")));

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), apiLaneId)
                                && Objects.equals(LaneStatus.COMPLETED, lane.getStatus()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("23333333-3333-3333-3333-333333333333"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
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
        this.laneCompletion.completeApiLane(ticketId, apiLaneId);

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsAllWithJsons(
                        "expectedApiCompleteImplementBeAutomationTicket.json",
                        "expectedApiCompleteImplementBeBffTicket.json"
                );

        this.testManager.mongo()
                .get(AgentTicketDocument.class)
                .hasSize(2)
                .andExpected(value -> Set.of(
                                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                                UUID.fromString("34444444-4444-4444-4444-444444444444")
                        ).contains(value.getLaneId()));

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), apiLaneId)
                                && Objects.equals(LaneStatus.COMPLETED, lane.getStatus()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("33333333-3333-3333-3333-333333333333"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("34444444-4444-4444-4444-444444444444"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
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
        this.laneCompletion.completeApiLane(ticketId, apiLaneId);

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
                .get(AgentTicketDocument.class)
                .hasSize(3)
                .andExpected(value -> Set.of(
                                UUID.fromString("43333333-3333-3333-3333-333333333333"),
                                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                                UUID.fromString("45555555-5555-5555-5555-555555555555")
                        ).contains(value.getLaneId()));

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), apiLaneId)
                                && Objects.equals(LaneStatus.COMPLETED, lane.getStatus()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("43333333-3333-3333-3333-333333333333"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("44444444-4444-4444-4444-444444444444"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), UUID.fromString("45555555-5555-5555-5555-555555555555"))
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
    }

    @Test
    @DisplayName("Should skip missing backend scope contracts and create tasks only for provided scopes in global API lane")
    void givenGlobalApiLaneWithPartialContracts_whenCompleteApiLane_thenCreateTasksOnlyForProvidedScopes() {
        //given
        final UUID ticketId = UUID.fromString("51111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("52222222-2222-2222-2222-222222222222");
        final UUID automationLaneId = UUID.fromString("53333333-3333-3333-3333-333333333333");
        final UUID bffLaneId = UUID.fromString("54444444-4444-4444-4444-444444444444");
        final UUID frontendLaneId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneTwoBeOneFePartialContractsSeedTicket.json");

        //when then
        this.laneCompletion.completeApiLane(ticketId, apiLaneId, "requestCompleteApiLanePartialScopes.json", request -> { });

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2);

        this.testManager.mongo()
                .get(AgentTicketDocument.class)
                .hasSize(2)
                .andExpected(value -> Set.of(bffLaneId, frontendLaneId).contains(value.getLaneId()));

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), apiLaneId)
                                && Objects.equals(LaneStatus.COMPLETED, lane.getStatus()))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), automationLaneId)
                                && Objects.equals(LaneStatus.NOT_STARTED, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), bffLaneId)
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1))
                        && value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), frontendLaneId)
                                && Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                && Objects.nonNull(lane.getInputTaskIds())
                                && Objects.equals(lane.getInputTaskIds().size(), 1)));
    }

}
