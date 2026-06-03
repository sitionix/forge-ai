package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
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
    private LaneCompletionTestFacade laneCompletion;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Should complete full SPA+BFF+automation flow with all lanes completed")
    void givenSpaBffAutomationScopes_whenCompleteAllCallbacks_thenAllLanesAreCompleted() {
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
        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerAutomationLaneId);

        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerBffLaneId, request -> {
                    request.getArchitectHandoff().setScope("backendforfrontendservice-sox");
                    request.getQaLeadHandoff().setScope("backendforfrontendservice-sox");
                });

        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerSpaLaneId, request -> {
                    request.getArchitectHandoff().setScope("sitionix-spa");
                    request.getQaLeadHandoff().setScope("sitionix-spa");
                });

        this.laneCompletion.completeArchitectLane(ticketId, architectAutomationLaneId, "requestCompleteArchitectLaneAutomationApiEventNotRequired.json", request -> { });

        this.laneCompletion.completeArchitectLane(ticketId, architectBffLaneId, "requestCompleteArchitectLaneBffApiRequiredEventNotRequired.json", request -> { });

        this.laneCompletion.completeArchitectLane(ticketId, architectSpaLaneId, "requestCompleteArchitectLaneFrontendApiRequired.json", request -> { });

        this.laneCompletion.completeApiLane(ticketId, apiLaneId, request -> {
            request.setPrUrl("https://github.com/sitionix/app-afesox/pull/143");
            request.setRepo("sitionix/app-afesox");
        });

        this.ticketRepository.updateLaneStatus(qaLeadAutomationLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadAutomationLaneId);

        this.ticketRepository.updateLaneStatus(qaLeadBffLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadBffLaneId, request -> request.setScope("backendforfrontendservice-sox"));

        this.ticketRepository.updateLaneStatus(qaLeadSpaLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadSpaLaneId, request -> {
                    request.setScope("sitionix-spa");
                    request.getTestLaneRequirements().setUnitTestRequired(false);
                    request.getTestLaneRequirements().setIntegrationTestRequired(false);
                    request.getTestLaneRequirements().setUiTestRequired(true);
                    request.setIntegrationTestCases(List.of());
                    request.setUnitTestNotes(List.of());
                });

        this.laneCompletion.completeImplementBeLane(ticketId, implementBeAutomationLaneId);

        this.laneCompletion.completeImplementBeLane(ticketId, implementBeBffLaneId,
                request -> request.setScope("backendforfrontendservice-sox"));

        this.laneCompletion.completeImplementFeLane(ticketId, implementFeSpaLaneId);

        this.laneCompletion.completeUnitTestLane(ticketId, testUnitAutomationLaneId);

        this.laneCompletion.completeUnitTestLane(ticketId, testUnitBffLaneId,
                request -> request.setScope("backendforfrontendservice-sox"));

        this.ticketRepository.updateLaneStatus(testItAutomationLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeItTestLane(ticketId, testItAutomationLaneId);

        this.ticketRepository.updateLaneStatus(testItBffLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeItTestLane(ticketId, testItBffLaneId, request -> request.setScope("backendforfrontendservice-sox"));

        this.ticketRepository.updateLaneStatus(testUiSpaLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeUiTestLane(ticketId, testUiSpaLaneId);

        // then
        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(actual.getLanes()).anyMatch(lane -> lane.getType() == Agent.EVENT && lane.getStatus() == LaneStatus.NOT_NEEDED);
        assertThat(actual.getLanes()).anyMatch(lane -> lane.getType() == Agent.REVIEWER
                && Set.of(LaneStatus.READY_TO_START, LaneStatus.IN_PROGRESS, LaneStatus.COMPLETED).contains(lane.getStatus()));
        assertThat(actual.getLanes().stream()
                .filter(lane -> lane.getType() != Agent.EVENT && lane.getType() != Agent.REVIEWER)
                .map(lane -> lane.getStatus())
                .toList())
                .allMatch(status -> status == LaneStatus.COMPLETED);
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
