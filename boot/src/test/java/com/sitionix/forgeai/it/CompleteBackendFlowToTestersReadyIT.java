package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000"
})
class CompleteBackendFlowToTestersReadyIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Test
    @DisplayName("Should drive backend analyzer, architect, api and implement_be flows until test lanes are ready")
    void givenBackendScopesWhenCodexRoutesCallbacksThenTestersBecomeReadyToStart() {
        //given
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .assertDefault();

        final TicketDocument ticket = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        final UUID ticketId = ticket.getId();
        final UUID analyzerAutomationLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.ANALYZER)
                        && Objects.equals(lane.getScope(), "automationservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID analyzerBffLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.ANALYZER)
                        && Objects.equals(lane.getScope(), "backendforfrontendservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID architectAutomationLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.ARCHITECT)
                        && Objects.equals(lane.getScope(), "automationservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID architectBffLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.ARCHITECT)
                        && Objects.equals(lane.getScope(), "backendforfrontendservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID apiLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.API))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID qaLeadAutomationLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.QA_LEAD)
                        && Objects.equals(lane.getScope(), "automationservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID qaLeadBffLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.QA_LEAD)
                        && Objects.equals(lane.getScope(), "backendforfrontendservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID implementBeAutomationLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.IMPLEMENT_BE)
                        && Objects.equals(lane.getScope(), "automationservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID implementBeBffLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.IMPLEMENT_BE)
                        && Objects.equals(lane.getScope(), "backendforfrontendservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID testUnitAutomationLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.TEST_UNIT)
                        && Objects.equals(lane.getScope(), "automationservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID testUnitBffLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.TEST_UNIT)
                        && Objects.equals(lane.getScope(), "backendforfrontendservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID testItAutomationLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.TEST_IT)
                        && Objects.equals(lane.getScope(), "automationservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID testItBffLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.TEST_IT)
                        && Objects.equals(lane.getScope(), "backendforfrontendservice-sox"))
                .findFirst()
                .orElseThrow()
                .getId();
        final UUID reviewerLaneId = ticket.getLanes().stream()
                .filter(lane -> Objects.equals(lane.getType(), Agent.REVIEWER))
                .findFirst()
                .orElseThrow()
                .getId();
        final ConcurrentHashMap<UUID, AtomicInteger> submissionCounters = new ConcurrentHashMap<>();

        doAnswer(invocation -> {
            final AgentExecutionInput<?> input = invocation.getArgument(0);
            submissionCounters.computeIfAbsent(input.getLaneId(), ignored -> new AtomicInteger(0)).incrementAndGet();
            if (Objects.equals(input.getLaneId(), analyzerAutomationLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeAnalyzerLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerAutomationLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), analyzerBffLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeAnalyzerLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerBffLaneId))
                        .assertDefault(d -> d.mutateRequest(request -> {
                            request.getArchitectHandoff().setScope("backendforfrontendservice-sox");
                            request.getQaLeadHandoff().setScope("backendforfrontendservice-sox");
                        }));
                return null;
            }
            if (Objects.equals(input.getLaneId(), architectAutomationLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeArchitectLane())
                        .withRequest("requestCompleteArchitectLaneAutomationApiEventNotRequired.json")
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectAutomationLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), architectBffLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeArchitectLane())
                        .withRequest("requestCompleteArchitectLaneBffApiEventNotRequired.json")
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", architectBffLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), apiLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeApiLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", apiLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), qaLeadAutomationLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadAutomationLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), qaLeadBffLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadBffLaneId))
                        .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));
                return null;
            }
            if (Objects.equals(input.getLaneId(), implementBeAutomationLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeImplementBeLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeAutomationLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), implementBeBffLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeImplementBeLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeBffLaneId))
                        .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));
                return null;
            }
            if (Objects.equals(input.getLaneId(), testUnitAutomationLaneId)) {
                assertThat(input.getTasks()).isNotNull();
                assertThat(input.getTasks()).anyMatch(TestUnitPayload.class::isInstance);
                assertThat(input.getTasks()).anyMatch(QaLeadTestUnitPayload.class::isInstance);
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeUnitTestLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testUnitAutomationLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), testUnitBffLaneId)) {
                assertThat(input.getTasks()).isNotNull();
                assertThat(input.getTasks()).anyMatch(TestUnitPayload.class::isInstance);
                assertThat(input.getTasks()).anyMatch(QaLeadTestUnitPayload.class::isInstance);
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeUnitTestLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testUnitBffLaneId))
                        .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));
                return null;
            }
            if (Objects.equals(input.getLaneId(), testItAutomationLaneId)) {
                assertThat(input.getTasks()).isNotNull();
                assertThat(input.getTasks()).anyMatch(TestItPayload.class::isInstance);
                assertThat(input.getTasks()).anyMatch(QaLeadTestItPayload.class::isInstance);
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeItTestLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testItAutomationLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), testItBffLaneId)) {
                assertThat(input.getTasks()).isNotNull();
                assertThat(input.getTasks()).anyMatch(TestItPayload.class::isInstance);
                assertThat(input.getTasks()).anyMatch(QaLeadTestItPayload.class::isInstance);
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeItTestLane())
                        .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", testItBffLaneId))
                        .assertDefault(d -> d.mutateRequest(request -> request.setScope("backendforfrontendservice-sox")));
                return null;
            }
            if (Objects.equals(input.getLaneId(), reviewerLaneId)) {
                return null;
            }
            throw new AssertionError("Unexpected Codex submit laneId=" + input.getLaneId() + ", input=" + input);
        }).when(this.codexClient).submit(any(AgentExecutionInput.class), anyString());

        //when
        this.readyToStartLaneJob.run();
        this.readyToStartLaneJob.run();
        this.readyToStartLaneJob.run();
        this.readyToStartLaneJob.run();
        this.readyToStartLaneJob.run();

        //then
        this.testManager.mongo()
                .assertEntities(com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument.class)
                .hasSize(18);

        assertThat(submissionCounters.get(testUnitAutomationLaneId).get()).isEqualTo(1);
        assertThat(submissionCounters.get(testUnitBffLaneId).get()).isEqualTo(1);
        assertThat(submissionCounters.get(testItAutomationLaneId).get()).isEqualTo(1);
        assertThat(submissionCounters.get(testItBffLaneId).get()).isEqualTo(1);

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        assertThat(actual.getLanes().stream()
                .filter(lane -> lane.getType() == Agent.TEST_UNIT || lane.getType() == Agent.TEST_IT)
                .allMatch(lane -> lane.getStatus().name().equals("COMPLETED")
                        || lane.getStatus().name().equals("READY_TO_START")
                        || lane.getStatus().name().equals("IN_PROGRESS"))).isTrue();
    }
}
