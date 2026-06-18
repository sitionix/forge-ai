package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.application.operator.TicketOperatorRunService;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketOperatorEventRepository;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.TicketJpaRepository;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneExecutionJpaRepository;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class TicketOperatorControllerIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketJpaRepository ticketJpaRepository;

    @Autowired
    private LaneExecutionJpaRepository laneExecutionJpaRepository;

    @Autowired
    private TicketOperatorRunService ticketOperatorRunService;

    @Autowired
    private TicketOperatorEventRepository ticketOperatorEventRepository;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @Test
    @DisplayName("Should aggregate only current ticket events in operator snapshot")
    void givenTwoTickets_whenGetTicketSnapshot_thenReturnOnlyCurrentTicketEvents() throws Exception {
        final TicketDocument ticketA = this.createTicket(true);
        final TicketDocument ticketB = this.createTicket(false);
        final UUID laneA1 = ticketA.getLanes().getFirst().getId();
        final UUID laneA2 = ticketA.getLanes().get(1).getId();
        final UUID laneB1 = ticketB.getLanes().getFirst().getId();

        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(ticketA.getId())
                .ticketKey(ticketA.getTicketKey())
                .laneId(laneA1)
                .executionId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
                .agentId("analyzer")
                .scope(ticketA.getLanes().getFirst().getScope())
                .eventType("LANE_STARTED")
                .message("lane-a1")
                .timestamp(Instant.now())
                .build());
        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(ticketA.getId())
                .ticketKey(ticketA.getTicketKey())
                .laneId(laneA2)
                .executionId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
                .agentId("architect")
                .scope(ticketA.getLanes().get(1).getScope())
                .eventType("STEP_STARTED")
                .message("lane-a2")
                .timestamp(Instant.now())
                .build());
        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(ticketB.getId())
                .ticketKey(ticketB.getTicketKey())
                .laneId(laneB1)
                .executionId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"))
                .agentId("analyzer")
                .scope(ticketB.getLanes().getFirst().getScope())
                .eventType("LANE_STARTED")
                .message("lane-b1")
                .timestamp(Instant.now())
                .build());

        this.mockMvc.perform(get("/api/v1/forge-ai/operator/tickets/{ticketId}", ticketA.getId())
                        .param("verbosity", "minimal")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.ticketId").value(ticketA.getId().toString()))
                .andExpect(jsonPath("$.recentEvents[?(@.message == 'lane-a1')]").isNotEmpty())
                .andExpect(jsonPath("$.recentEvents[?(@.message == 'lane-a2')]").isNotEmpty())
                .andExpect(jsonPath("$.recentEvents[?(@.message == 'lane-b1')]").isEmpty());

        assertThat(this.ticketOperatorEventRepository.findRecentByTicketId(ticketA.getId(), 500))
                .extracting(TicketOperatorEvent::getMessage)
                .contains("lane-a1", "lane-a2")
                .doesNotContain("lane-b1");
    }

    @Test
    @DisplayName("Should interrupt only current ticket and block future lane starts for that ticket")
    void givenCancelledTicket_whenInterruptAndRunScheduler_thenInterruptOnlyThatTicketAndSkipFutureStarts() throws Exception {
        final TicketDocument ticketA = this.createTicket(true);
        final TicketDocument ticketB = this.createTicket(false);
        final UUID executionA = UUID.fromString("dddddddd-1111-1111-1111-111111111111");
        final UUID executionB = UUID.fromString("eeeeeeee-1111-1111-1111-111111111111");

        this.laneExecutionJpaRepository.save(this.executionDocument(ticketA, executionA, "turn-a"));
        this.laneExecutionJpaRepository.save(this.executionDocument(ticketB, executionB, "turn-b"));

        this.mockMvc.perform(post("/api/v1/forge-ai/operator/tickets/{ticketId}/interrupt", ticketA.getId())
                        .param("reason", "OPERATOR_TICKET_TERMINAL_CLOSED")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticketA.getId().toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(this.codexSessionRepositoryStub.interruptedTurns()).contains("turn-a");
        assertThat(this.codexSessionRepositoryStub.interruptedTurns()).doesNotContain("turn-b");
        assertThat(this.laneExecutionJpaRepository.findById(executionA).orElseThrow().getStatus()).isEqualTo(LaneExecutionStatus.INTERRUPTED);
        assertThat(this.laneExecutionJpaRepository.findById(executionB).orElseThrow().getStatus()).isEqualTo(LaneExecutionStatus.TURN_RUNNING);

        this.codexSessionRepositoryStub.clearSentMessages();
        this.readyToStartLaneJob.run();

        this.eventually(Duration.ofSeconds(30), () -> {
            final List<String> prompts = this.codexSessionRepositoryStub.sentMessages();
            assertThat(prompts).isNotEmpty();
            assertThat(prompts).allMatch(prompt -> !prompt.contains(ticketA.getId().toString()));
            assertThat(prompts).anyMatch(prompt -> prompt.contains(ticketB.getId().toString()));

            final TicketDocument cancelledTicket = this.ticketJpaRepository.findById(ticketA.getId()).orElseThrow();
            final TicketDocument runningTicket = this.ticketJpaRepository.findById(ticketB.getId()).orElseThrow();
            assertThat(this.statusOf(cancelledTicket, Agent.ANALYZER)).isEqualTo(LaneStatus.READY_TO_START);
            assertThat(this.statusOf(runningTicket, Agent.ANALYZER)).isNotEqualTo(LaneStatus.READY_TO_START);
        });
    }

    private TicketDocument createTicket(final boolean backend) {
        if (backend) {
            this.testManager.mockMvc().ping(ControllerEndpoint.startForge()).assertDefault();
        } else {
            this.testManager.mockMvc().ping(ControllerEndpoint.startForgeFrontend()).assertDefault();
        }
        return this.ticketJpaRepository.findAll().stream()
                .max(Comparator.comparing(TicketDocument::getCreatedAt))
                .orElseThrow();
    }

    private LaneExecutionDocument executionDocument(final TicketDocument ticket, final UUID executionId, final String turnId) {
        final LaneDocument analyzerLane = ticket.getLanes().stream()
                .filter(lane -> lane.getType() == Agent.ANALYZER)
                .findFirst()
                .orElseThrow();
        final LaneExecutionDocument document = new LaneExecutionDocument();
        document.setId(executionId);
        document.setTicketId(ticket.getId());
        document.setLaneId(analyzerLane.getId());
        document.setAgentId("analyzer");
        document.setScope(analyzerLane.getScope());
        document.setStrategyId("analyzer");
        document.setStrategyVersion(1);
        document.setStatus(LaneExecutionStatus.TURN_RUNNING);
        document.setSessionId("session-" + executionId);
        document.setThreadId("thr-" + executionId);
        document.setActiveTurnId(turnId);
        document.setProcessPid(91342L);
        document.setProcessCommand("codex app-server --stdio");
        document.setProcessCwd("/workspace");
        document.setCodexVersion("fake");
        document.setProcessStartedAt(LocalDateTime.now().minusSeconds(30));
        document.setCurrentStepId("scope_slicing");
        document.setCurrentStepOrder(1);
        document.setCurrentStepTitle("Scope slicing");
        document.setLastProgressEvent("TURN_STARTED");
        document.setLastProgressAt(LocalDateTime.now().minusSeconds(5));
        document.setLastCodexEventType("TURN_STARTED");
        document.setStartedAt(LocalDateTime.now().minusMinutes(1));
        document.setUpdatedAt(LocalDateTime.now().minusSeconds(5));
        return document;
    }

    private LaneStatus statusOf(final TicketDocument ticket, final Agent agent) {
        return ticket.getLanes().stream()
                .filter(lane -> lane.getType() == agent)
                .findFirst()
                .map(LaneDocument::getStatus)
                .orElseThrow();
    }
}
