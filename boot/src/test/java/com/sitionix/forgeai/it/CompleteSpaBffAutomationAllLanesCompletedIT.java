package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000"
})
class CompleteSpaBffAutomationAllLanesCompletedIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Should complete full SPA+BFF+automation flow with all lanes closed")
    void givenSpaBffAutomationScopes_whenCompleteAllCallbacks_thenAllLanesAreCompletedOrNotNeeded() {
        // given
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.setTicket("SITIONIX-77");
                    request.setTask("full mixed flow complete");
                    request.setServiceIds(List.of("atmssox", "bffssox", "sitionix-spa"));
                }));

        final TicketDocument ticket = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        final UUID ticketId = ticket.getId();
        final UUID analyzerAutomationLaneId = this.findLaneId(ticket, Agent.ANALYZER, "automationservice-sox");
        final UUID analyzerBffLaneId = this.findLaneId(ticket, Agent.ANALYZER, "backendforfrontendservice-sox");
        final UUID analyzerSpaLaneId = this.findLaneId(ticket, Agent.ANALYZER, "sitionix-spa");
        final UUID architectAutomationLaneId = this.findLaneId(ticket, Agent.ARCHITECT, "automationservice-sox");
        final UUID architectBffLaneId = this.findLaneId(ticket, Agent.ARCHITECT, "backendforfrontendservice-sox");
        final UUID architectSpaLaneId = this.findLaneId(ticket, Agent.ARCHITECT, "sitionix-spa");
        final UUID apiLaneId = this.findLaneId(ticket, Agent.API, "GLOBAL");
        final UUID qaLeadAutomationLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "automationservice-sox");
        final UUID qaLeadBffLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "backendforfrontendservice-sox");
        final UUID qaLeadSpaLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "sitionix-spa");
        final UUID implementBeAutomationLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_BE, "automationservice-sox");
        final UUID implementBeBffLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_BE, "backendforfrontendservice-sox");
        final UUID implementFeSpaLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_FE, "sitionix-spa");
        final UUID testUnitAutomationLaneId = this.findLaneId(ticket, Agent.TEST_UNIT, "automationservice-sox");
        final UUID testUnitBffLaneId = this.findLaneId(ticket, Agent.TEST_UNIT, "backendforfrontendservice-sox");
        final UUID testItAutomationLaneId = this.findLaneId(ticket, Agent.TEST_IT, "automationservice-sox");
        final UUID testItBffLaneId = this.findLaneId(ticket, Agent.TEST_IT, "backendforfrontendservice-sox");
        final UUID testUiSpaLaneId = this.findLaneId(ticket, Agent.TEST_UI, "sitionix-spa");

        // when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerAutomationLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerBffLaneId))
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.getArchitectHandoff().setScope("backendforfrontendservice-sox");
                    request.getQaLeadHandoff().setScope("backendforfrontendservice-sox");
                }));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerSpaLaneId))
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.getArchitectHandoff().setScope("sitionix-spa");
                    request.getQaLeadHandoff().setScope("sitionix-spa");
                }));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneAutomationApiEventNotRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectAutomationLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneBffApiRequiredEventNotRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectBffLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneFrontendApiRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectSpaLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .assertDefault();

        this.ticketRepository.updateLaneStatus(qaLeadAutomationLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadAutomationLaneId))
                .assertDefault();

        this.ticketRepository.updateLaneStatus(qaLeadBffLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadBffLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        this.ticketRepository.updateLaneStatus(qaLeadSpaLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadSpaLaneId))
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.setScope("sitionix-spa");
                    request.getTestLaneRequirements().setUnitTestRequired(false);
                    request.getTestLaneRequirements().setIntegrationTestRequired(false);
                    request.getTestLaneRequirements().setUiTestRequired(true);
                    request.setIntegrationTestCases(List.of());
                    request.setUnitTestNotes(List.of());
                }));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementBeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeAutomationLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementBeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeBffLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementFeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementFeSpaLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeUnitTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testUnitAutomationLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeUnitTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testUnitBffLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        this.ticketRepository.updateLaneStatus(testItAutomationLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeItTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testItAutomationLaneId))
                .assertDefault();

        this.ticketRepository.updateLaneStatus(testItBffLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeItTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testItBffLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        this.ticketRepository.updateLaneStatus(testUiSpaLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeUiTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testUiSpaLaneId))
                .assertDefault();

        // then
        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(actual.getLanes())
                .extracting(lane -> lane.getStatus())
                .allMatch(status -> Set.of(LaneStatus.COMPLETED, LaneStatus.NOT_NEEDED).contains(status));
    }

    private UUID findLaneId(final TicketDocument ticket, final Agent agent, final String scope) {
        return ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), agent))
                .filter(lane -> Objects.equals(lane.getScope(), scope))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
