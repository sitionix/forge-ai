package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteImplementFeLaneFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should store frontend completion report and complete implement_fe lane while marking test_ui not needed")
    void givenCompleteImplementFePayload_whenCompleteImplementFeLane_thenStoreReportAndCompleteLane() {
        //given
        final UUID ticketId = UUID.fromString("a1111111-1111-1111-1111-111111111111");
        final UUID laneId = UUID.fromString("a2222222-2222-2222-2222-222222222222");
        final UUID testUiLaneId = UUID.fromString("a3333333-3333-3333-3333-333333333333");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementFeLaneSeedTicket.json");

        final AgentTicketDocument agentTicketDocument = new AgentTicketDocument();
        agentTicketDocument.setId(UUID.fromString("a4444444-4444-4444-4444-444444444444"));
        agentTicketDocument.setTicketId(ticketId);
        agentTicketDocument.setLaneId(laneId);
        agentTicketDocument.setStatus(AgentTicketStatus.CREATED);
        agentTicketDocument.setScope("sitionix-spa");
        agentTicketDocument.setAgent(Agent.IMPLEMENT_FE);
        agentTicketDocument.setPayload(this.getSeedImplementFePayload());
        agentTicketDocument.setCreatedAt(LocalDateTime.parse("2026-01-01T10:00:00"));
        agentTicketDocument.setUpdatedAt(LocalDateTime.parse("2026-01-01T10:00:00"));
        this.testManager.mongo()
                .create(AgentTicketDocument.class)
                .body(agentTicketDocument);

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementFeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", laneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(laneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2)
                .containsAllWithJsons(
                        "expectedCompleteImplementFeLaneReportTicket.json"
                );

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> {
                    final boolean implementFeCompleted = value.getLanes().stream()
                            .anyMatch(lane -> Objects.equals(lane.getId(), laneId)
                                    && Objects.equals(LaneStatus.COMPLETED, lane.getStatus()));
                    final boolean testUiNotNeeded = value.getLanes().stream()
                            .anyMatch(lane -> Objects.equals(lane.getId(), testUiLaneId)
                                    && Objects.equals(LaneStatus.NOT_NEEDED, lane.getStatus()));
                    return implementFeCompleted && testUiNotNeeded;
                });
    }

    private ImplementFePayload getSeedImplementFePayload() {
        return ImplementFePayload.builder()
                .task("Implement API contract integration for sitionix-spa")
                .scope("sitionix-spa")
                .summary("Connect SPA flow to generated BFF contract.")
                .requirements(Set.of("Use generated frontend package"))
                .constraints(Set.of("Preserve current page structure"))
                .nonGoals(Set.of("No backend changes"))
                .architectureDecision("Use generated client artifacts from API lane.")
                .dependencies(Set.of("pnpm add @sitionix/app-afesox-bffssox-frontend-sitionix-1222-unstable@0.0.41"))
                .acceptanceNotes(Set.of("Frontend API contract available for SPA scope."))
                .risks(Set.of("Ensure generated hook usage matches current route flow."))
                .build();
    }
}
