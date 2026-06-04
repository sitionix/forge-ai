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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000"
})
class CompleteFrontendBackendFlowToReviewerReadyIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Should drive mixed backend and frontend flows until backend and frontend test lanes are completed")
    void givenFrontendAndBackendScopesWhenLaneCompletionsRouteOutputsThenReviewerBecomesReadyAndFrontendCompletes() {
        //given
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.setTicket("SITIONIX-13");
                    request.setTask("mixed frontend backend flow");
                    request.setServiceIds(List.of("bffssox", "sitionix-spa"));
                }));

        final TicketDocument ticket = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        final UUID ticketId = ticket.getId();
        final UUID analyzerBffLaneId = this.findLaneId(ticket, Agent.ANALYZER, "backendforfrontendservice-sox");
        final UUID analyzerFrontendLaneId = this.findLaneId(ticket, Agent.ANALYZER, "sitionix-spa");
        final UUID architectBffLaneId = this.findLaneId(ticket, Agent.ARCHITECT, "backendforfrontendservice-sox");
        final UUID architectFrontendLaneId = this.findLaneId(ticket, Agent.ARCHITECT, "sitionix-spa");
        final UUID apiLaneId = this.findLaneId(ticket, Agent.API, "GLOBAL");
        final UUID qaLeadBffLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "backendforfrontendservice-sox");
        final UUID implementBeLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_BE, "backendforfrontendservice-sox");
        final UUID implementFeLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_FE, "sitionix-spa");
        final UUID testUnitLaneId = this.findLaneId(ticket, Agent.TEST_UNIT, "backendforfrontendservice-sox");
        final UUID testItLaneId = this.findLaneId(ticket, Agent.TEST_IT, "backendforfrontendservice-sox");
        final UUID reviewerLaneId = this.findLaneId(ticket, Agent.REVIEWER, "GLOBAL");
        final UUID qaLeadSpaLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "sitionix-spa");
        final UUID testUiLaneId = this.findLaneId(ticket, Agent.TEST_UI, "sitionix-spa");

        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerBffLaneId, request -> {
                    request.getArchitectHandoff().setScope("backendforfrontendservice-sox");
                    request.getQaLeadHandoff().setScope("backendforfrontendservice-sox");
                });

        this.laneCompletion.completeAnalyzerLane(ticketId, analyzerFrontendLaneId, request -> {
                    request.getArchitectHandoff().setScope("sitionix-spa");
                    request.getQaLeadHandoff().setScope("sitionix-spa");
                });

        final TicketDocument afterAnalyzerCompletion = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(this.getLaneStatus(afterAnalyzerCompletion, analyzerBffLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(afterAnalyzerCompletion, analyzerFrontendLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(afterAnalyzerCompletion, architectBffLaneId)).isEqualTo(LaneStatus.READY_TO_START);
        assertThat(this.getLaneStatus(afterAnalyzerCompletion, architectFrontendLaneId)).isEqualTo(LaneStatus.READY_TO_START);
        assertThat(this.getLaneStatus(afterAnalyzerCompletion, qaLeadBffLaneId)).isEqualTo(LaneStatus.READY_TO_START);

        this.laneCompletion.completeArchitectLane(ticketId, architectBffLaneId, "requestCompleteArchitectLaneBffApiRequiredEventNotRequired.json", request -> { });

        this.laneCompletion.completeArchitectLane(ticketId, architectFrontendLaneId, "requestCompleteArchitectLaneFrontendApiRequired.json", request -> { });

        this.laneCompletion.completeApiLane(ticketId, apiLaneId, request -> {
            request.getContracts().removeIf(value -> Objects.equals(value.getScope(), "automationservice-sox"));
            request.setPrUrl("https://github.com/sitionix/app-afesox/pull/143");
            request.setRepo("sitionix/app-afesox");
        });

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

        this.laneCompletion.completeImplementBeLane(ticketId, implementBeLaneId,
                request -> request.setScope("backendforfrontendservice-sox"));

        this.ticketRepository.updateLaneStatus(implementFeLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeImplementFeLane(ticketId, implementFeLaneId);

        this.laneCompletion.completeUnitTestLane(ticketId, testUnitLaneId,
                request -> request.setScope("backendforfrontendservice-sox"));

        this.ticketRepository.updateLaneStatus(testItLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeItTestLane(ticketId, testItLaneId, request -> request.setScope("backendforfrontendservice-sox"));
        this.ticketRepository.updateLaneStatus(testUiLaneId, LaneStatus.IN_PROGRESS);
        this.laneCompletion.completeUiTestLane(ticketId, testUiLaneId);

        //then
        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(this.getLaneStatus(actual, implementFeLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, qaLeadBffLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, qaLeadSpaLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, implementBeLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, testUnitLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, testItLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, testUiLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, reviewerLaneId)).isEqualTo(LaneStatus.READY_TO_START);
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
