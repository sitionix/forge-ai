package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000"
})
class CompleteBackendFlowToTestersReadyIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Should drive backend upstream lanes until tester lanes are ready to start")
    void givenBackendScopes_whenUpstreamLanesComplete_thenTestersBecomeReadyToStart() {
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .assertDefault();

        final TicketDocument ticket = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        final UUID ticketId = ticket.getId();
        final UUID analyzerAutomationLaneId = this.findLaneId(ticket, Agent.ANALYZER, "automationservice-sox");
        final UUID analyzerBffLaneId = this.findLaneId(ticket, Agent.ANALYZER, "backendforfrontendservice-sox");
        final UUID architectAutomationLaneId = this.findLaneId(ticket, Agent.ARCHITECT, "automationservice-sox");
        final UUID architectBffLaneId = this.findLaneId(ticket, Agent.ARCHITECT, "backendforfrontendservice-sox");
        final UUID apiLaneId = this.findLaneId(ticket, Agent.API, "GLOBAL");
        final UUID qaLeadAutomationLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "automationservice-sox");
        final UUID qaLeadBffLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "backendforfrontendservice-sox");
        final UUID implementBeAutomationLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_BE, "automationservice-sox");
        final UUID implementBeBffLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_BE, "backendforfrontendservice-sox");
        final UUID testUnitAutomationLaneId = this.findLaneId(ticket, Agent.TEST_UNIT, "automationservice-sox");
        final UUID testUnitBffLaneId = this.findLaneId(ticket, Agent.TEST_UNIT, "backendforfrontendservice-sox");
        final UUID testItAutomationLaneId = this.findLaneId(ticket, Agent.TEST_IT, "automationservice-sox");
        final UUID testItBffLaneId = this.findLaneId(ticket, Agent.TEST_IT, "backendforfrontendservice-sox");
        final UUID reviewerLaneId = this.findLaneId(ticket, Agent.REVIEWER, "GLOBAL");

        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerBffLaneId, request -> {
                    request.getArchitectHandoff().setScope("backendforfrontendservice-sox");
                    request.getQaLeadHandoff().setScope("backendforfrontendservice-sox");
                });

        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerAutomationLaneId);

        this.laneCompletion.completeArchitectLane(ticketId, architectAutomationLaneId, "requestCompleteArchitectLaneAutomationApiEventNotRequired.json", request -> { });

        this.laneCompletion.completeArchitectLane(ticketId, architectBffLaneId, "requestCompleteArchitectLaneBffApiRequiredEventNotRequired.json", request -> { });

        this.laneCompletion.completeApiLane(ticketId, apiLaneId, request -> {
            request.setPrUrl("https://github.com/sitionix/app-afesox/pull/143");
            request.setRepo("sitionix/app-afesox");
        });

        this.ticketRepository.updateLaneStatus(qaLeadAutomationLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadAutomationLaneId);

        this.ticketRepository.updateLaneStatus(qaLeadBffLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeQaLeadLaneBackend(ticketId, qaLeadBffLaneId, request -> request.setScope("backendforfrontendservice-sox"));

        this.laneCompletion.completeImplementBeLane(ticketId, implementBeAutomationLaneId);

        this.laneCompletion.completeImplementBeLane(ticketId, implementBeBffLaneId,
                request -> request.setScope("backendforfrontendservice-sox"));

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(this.getLaneStatus(actual, analyzerAutomationLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, analyzerBffLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, architectAutomationLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, architectBffLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, apiLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, qaLeadAutomationLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, qaLeadBffLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, implementBeAutomationLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, implementBeBffLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, testUnitAutomationLaneId)).isEqualTo(LaneStatus.READY_TO_START);
        assertThat(this.getLaneStatus(actual, testUnitBffLaneId)).isEqualTo(LaneStatus.READY_TO_START);
        assertThat(this.getLaneStatus(actual, testItAutomationLaneId)).isEqualTo(LaneStatus.READY_TO_START);
        assertThat(this.getLaneStatus(actual, testItBffLaneId)).isEqualTo(LaneStatus.READY_TO_START);
        assertThat(this.getLaneStatus(actual, reviewerLaneId)).isEqualTo(LaneStatus.NOT_STARTED);
    }

    private UUID findLaneId(final TicketDocument ticket, final Agent agent, final String scope) {
        return ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), agent))
                .filter(lane -> Objects.equals(lane.getScope(), scope))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private LaneStatus getLaneStatus(final TicketDocument ticket, final UUID laneId) {
        return ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getId(), laneId))
                .findFirst()
                .orElseThrow()
                .getStatus();
    }
}
