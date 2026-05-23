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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CompleteFrontendBackendFlowToReviewerReadyIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Should drive mixed backend and frontend flows until reviewer is ready and deferred UI lanes do not block completion")
    void givenFrontendAndBackendScopesWhenCodexRoutesCallbacksThenReviewerBecomesReadyAndFrontendCompletes() {
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
        final UUID eventLaneId = this.findLaneId(ticket, Agent.EVENT, "GLOBAL");
        final UUID qaLeadBffLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "backendforfrontendservice-sox");
        final UUID qaLeadFrontendLaneId = this.findLaneId(ticket, Agent.QA_LEAD, "sitionix-spa");
        final UUID implementBeLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_BE, "backendforfrontendservice-sox");
        final UUID implementFeLaneId = this.findLaneId(ticket, Agent.IMPLEMENT_FE, "sitionix-spa");
        final UUID testUnitLaneId = this.findLaneId(ticket, Agent.TEST_UNIT, "backendforfrontendservice-sox");
        final UUID testItLaneId = this.findLaneId(ticket, Agent.TEST_IT, "backendforfrontendservice-sox");
        final UUID reviewerLaneId = this.findLaneId(ticket, Agent.REVIEWER, "GLOBAL");
        final UUID testUiLaneId = this.findLaneId(ticket, Agent.TEST_UI, "sitionix-spa");

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerBffLaneId))
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.getArchitectHandoff().setScope("backendforfrontendservice-sox");
                    request.getQaLeadHandoff().setScope("backendforfrontendservice-sox");
                }));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerFrontendLaneId))
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.getArchitectHandoff().setScope("sitionix-spa");
                    request.getQaLeadHandoff().setScope("sitionix-spa");
                }));

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
        assertThat(this.getLaneStatus(afterAnalyzerCompletion, qaLeadFrontendLaneId)).isEqualTo(LaneStatus.READY_TO_START);

        this.ticketRepository.updateLaneStatus(qaLeadFrontendLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackendNotRequired())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadFrontendLaneId))
                .assertDefault(d -> d.mutateRequest(request -> {
                    request.setScope("sitionix-spa");
                    request.setSummary("Prepared QA context for deferred UI testing.");
                }));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneBffApiRequiredEventNotRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectBffLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeArchitectLane())
                .withRequest("requestCompleteArchitectLaneFrontendApiRequired.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectFrontendLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeApiLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                .assertDefault(d -> d.mutateRequest(request ->
                        request.getContracts().removeIf(value -> Objects.equals(value.getScope(), "automationservice-sox"))));

        this.ticketRepository.updateLaneStatus(qaLeadBffLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadBffLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementBeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        this.ticketRepository.updateLaneStatus(implementFeLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementFeLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementFeLaneId))
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeUnitTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testUnitLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        this.ticketRepository.updateLaneStatus(testItLaneId, LaneStatus.IN_PROGRESS);
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeItTestLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testItLaneId))
                .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));

        //then
        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();

        assertThat(this.getLaneStatus(actual, implementFeLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, testUiLaneId)).isEqualTo(LaneStatus.NOT_NEEDED);
        assertThat(this.getLaneStatus(actual, reviewerLaneId)).isEqualTo(LaneStatus.READY_TO_START);
        assertThat(this.getLaneStatus(actual, qaLeadFrontendLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, qaLeadBffLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, implementBeLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, testUnitLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, testItLaneId)).isEqualTo(LaneStatus.COMPLETED);
        assertThat(this.getLaneStatus(actual, eventLaneId)).isEqualTo(LaneStatus.NOT_NEEDED);
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
