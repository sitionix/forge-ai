package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
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
class ScopeMismatchCallbacksIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

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
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLaneScopeMismatch())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.error").value("scope_mismatch"))
                .assertDefault();

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
    @DisplayName("Should fail api completion when callback contains scope outside produced implementation lanes")
    void givenApiLane_whenCompleteApiWithUnexpectedContractScope_thenReturnBadRequestAndDoNotCreateTasks() {
        //given
        final UUID ticketId = UUID.fromString("31111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("32222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneTwoBeSeedTicket.json");

        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLaneScopeMismatch())
                .withRequest("requestCompleteApiLaneScopeMismatch.json", request -> {
                    request.setPrUrl("https://github.com/sitionix/app-afesox/pull/164");
                    request.setRepo("sitionix/app-afesox");
                })
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.error").value("api_evidence_dependency_missing"))
                .assertDefault();

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
    @DisplayName("Should fail api completion when required scope dependency evidence is missing")
    void givenApiLane_whenCompleteApiWithoutRequiredDependencyEvidence_thenReturnBadRequestWithHint() {
        //given
        final UUID ticketId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        final UUID apiLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeApiLaneOneBeSeedTicket.json");

        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLaneScopeMismatch())
                .withRequest("requestCompleteApiLaneMissingRequiredDependencyEvidence.json", request -> {
                    request.setPrUrl("https://github.com/sitionix/app-afesox/pull/164");
                    request.setRepo("sitionix/app-afesox");
                    request.getContracts().removeIf(value -> Objects.equals(value.getScope(), "automationservice-sox"));
                })
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.error").value("scope_mismatch"))
                .assertDefault();

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
                                && Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus())));
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
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLaneScopeMismatch())
                .withRequest("requestCompleteApiLaneMissingRequiredDependencyEvidence.json", request -> {
                    request.setPrUrl("https://github.com/sitionix/app-afesox/pull/164");
                    request.setRepo("app-afesox");
                })
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.error").value("api_evidence_repo_format_invalid"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.hint").value("Set repo in owner/repo format (for example: sitionix/app-afesox)."))
                .assertDefault();
    }
}
