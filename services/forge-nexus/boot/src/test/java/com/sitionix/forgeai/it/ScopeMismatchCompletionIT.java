package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.domain.exception.ApiLaneEvidenceValidationException;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class ScopeMismatchCompletionIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should fail architect completion when implementation scope does not match lane scope")
    void givenArchitectLane_whenCompleteArchitectWithMismatchedScope_thenReturnBadRequestAndDoNotCreateTasks() {
        //given
        final UUID ticketId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        final UUID architectLaneId = UUID.fromString("66666666-6666-6666-6666-666666666666");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeArchitectLaneSeedTicket.json");

        //when
        assertThatThrownBy(() -> this.laneCompletion.completeArchitectLane(
                ticketId,
                architectLaneId,
                "requestCompleteArchitectLane.json",
                request -> request.getImplementationHandoff().setScope("backendforfrontendservice-sox")))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessage("Completion output scope mismatch: sourceLaneId=66666666-6666-6666-6666-666666666666, sourceAgent=architect, targetAgent=implement_be, expectedScope=automationservice-sox, actualScope=backendforfrontendservice-sox");

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), architectLaneId)
                                && Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus()))
                        && value.getLanes().stream()
                        .filter(lane -> !Objects.equals(lane.getId(), architectLaneId))
                        .allMatch(lane -> Objects.isNull(lane.getInputTaskIds()) || lane.getInputTaskIds().isEmpty()));
    }

    @Test
    @DisplayName("Should fail api completion when evidence misses generated dependency for produced scope")
    void givenApiLane_whenCompleteApiWithUnexpectedContractScope_thenReturnBadRequestAndDoNotCreateTasks() {
        //given
        final UUID ticketId = UUID.fromString("31111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("32222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneTwoBeSeedTicket.json");

        //when
        assertThatThrownBy(() -> this.laneCompletion.completeApiLane(ticketId, apiLaneId, "requestCompleteApiLaneScopeMismatch.json", request -> {
                    request.setPrUrl("https://github.com/sitionix/app-afesox/pull/164");
                    request.setRepo("sitionix/app-afesox");
                }))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("missing generated dependency evidence");

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), apiLaneId)
                                && Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus()))
                        && value.getLanes().stream()
                        .filter(lane -> !Objects.equals(lane.getId(), apiLaneId))
                        .allMatch(lane -> Objects.isNull(lane.getInputTaskIds()) || lane.getInputTaskIds().isEmpty()));
    }

    @Test
    @DisplayName("Should skip produced implementation lane when API completion omits its contract")
    void givenApiLane_whenCompleteApiWithoutContractForProducedScope_thenDoNotCreateTaskForMissingScope() {
        //given
        final UUID ticketId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneOneBeSeedTicket.json");

        //when
        this.laneCompletion.completeApiLane(ticketId, apiLaneId, "requestCompleteApiLaneMissingRequiredDependencyEvidence.json", request -> {
                    request.setPrUrl("https://github.com/sitionix/app-afesox/pull/164");
                    request.setRepo("sitionix/app-afesox");
                    request.getContracts().removeIf(value -> Objects.equals(value.getScope(), "automationservice-sox"));
                });

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), apiLaneId)
                                && Objects.equals(LaneStatus.COMPLETED, lane.getStatus())));
    }

    @Test
    @DisplayName("Should fail api completion when evidence repo format is invalid")
    void givenApiLane_whenCompleteApiWithInvalidRepoFormat_thenReturnBadRequestWithHint() {
        //given
        final UUID ticketId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneOneBeSeedTicket.json");

        //when
        assertThatThrownBy(() -> this.laneCompletion.completeApiLane(ticketId, apiLaneId, "requestCompleteApiLaneMissingRequiredDependencyEvidence.json", request -> {
                    request.setPrUrl("https://github.com/sitionix/app-afesox/pull/164");
                    request.setRepo("app-afesox");
                }))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("repo");
    }
}
