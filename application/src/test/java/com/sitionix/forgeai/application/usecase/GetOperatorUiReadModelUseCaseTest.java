package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.operator.TicketOperatorEventService;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneDetailResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneEvent;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneNode;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneStep;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetOperatorUiReadModelUseCaseTest {

    private static final UUID TICKET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ANALYZER_LANE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARCHITECT_LANE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EXECUTION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REVIEWER_LANE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID NOT_NEEDED_LANE_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID INPUT_TASK_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private GetOperatorUiReadModel useCase;

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private LaneExecutionRepository laneExecutionRepository;
    @Mock
    private AgentTicketRepository agentTicketRepository;
    @Mock
    private LaneStrategyRepository laneStrategyRepository;
    @Mock
    private TicketOperatorRunRepository ticketOperatorRunRepository;
    @Mock
    private TicketOperatorEventService ticketOperatorEventService;

    @BeforeEach
    void setUp() {
        this.useCase = new GetOperatorUiReadModelUseCase(
                this.ticketRepository,
                this.laneExecutionRepository,
                this.agentTicketRepository,
                this.laneStrategyRepository,
                this.ticketOperatorRunRepository,
                this.ticketOperatorEventService,
                new ObjectMapper()
        );
    }

    @Test
    void givenTicket_whenGraph_thenReturnLaneDependenciesAndExecutionStep() {
        final Ticket ticket = Ticket.builder()
                .id(TICKET_ID)
                .ticketKey("SITIONIX-142")
                .taskDescription("task")
                .status(TicketStatus.OPEN)
                .createdAt(LocalDateTime.parse("2026-06-05T10:00:00"))
                .lanes(List.of(
                        Lane.builder()
                                .id(ANALYZER_LANE_ID)
                                .agent(Agent.ANALYZER)
                                .scope("automationservice-sox")
                                .status(LaneStatus.COMPLETED)
                                .dependsOn(new LinkedHashSet<>())
                                .build(),
                        Lane.builder()
                                .id(ARCHITECT_LANE_ID)
                                .agent(Agent.ARCHITECT)
                                .scope("automationservice-sox")
                                .status(LaneStatus.IN_PROGRESS)
                                .dependsOn(new LinkedHashSet<>(List.of(LaneDependency.builder()
                                        .type(Agent.ANALYZER)
                                        .scope("automationservice-sox")
                                        .build())))
                                .build(),
                        Lane.builder()
                                .id(REVIEWER_LANE_ID)
                                .agent(Agent.REVIEWER)
                                .scope("GLOBAL")
                                .status(LaneStatus.NOT_STARTED)
                                .dependsOn(new LinkedHashSet<>())
                                .build(),
                        Lane.builder()
                                .id(NOT_NEEDED_LANE_ID)
                                .agent(Agent.TEST_IT)
                                .scope("automationservice-sox")
                                .status(LaneStatus.NOT_NEEDED)
                                .dependsOn(new LinkedHashSet<>(List.of(LaneDependency.builder()
                                        .type(Agent.IMPLEMENT_BE)
                                        .scope("automationservice-sox")
                                        .build())))
                                .build()
                ))
                .build();
        final LaneExecution execution = LaneExecution.builder()
                .id(EXECUTION_ID)
                .ticketId(TICKET_ID)
                .laneId(ARCHITECT_LANE_ID)
                .agentId("architect")
                .scope("automationservice-sox")
                .status(LaneExecutionStatus.TURN_RUNNING)
                .currentStepId("architecture_direction")
                .currentStepOrder(2)
                .currentStepTitle("Architecture Direction")
                .startedAt(LocalDateTime.parse("2026-06-05T10:01:00"))
                .build();
        when(this.ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(this.laneExecutionRepository.findByTicketId(TICKET_ID)).thenReturn(List.of(execution));
        when(this.ticketOperatorRunRepository.findByTicketId(TICKET_ID)).thenReturn(Optional.empty());

        final OperatorUiTicketGraphResponse actual = this.useCase.graph(TICKET_ID);

        assertThat(actual.ticketId()).isEqualTo(TICKET_ID);
        assertThat(actual.lanes()).hasSize(3);
        assertThat(actual.lanes()).extracting(OperatorUiLaneNode::laneId)
                .contains(REVIEWER_LANE_ID)
                .doesNotContain(NOT_NEEDED_LANE_ID);
        assertThat(actual.laneCounts().completed()).isEqualTo(1);
        assertThat(actual.laneCounts().inProgress()).isEqualTo(1);
        assertThat(actual.laneCounts().notNeeded()).isZero();
        final OperatorUiLaneNode architect = actual.lanes().get(1);
        assertThat(architect.laneId()).isEqualTo(ARCHITECT_LANE_ID);
        assertThat(architect.dependencies()).singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.agent()).isEqualTo("ANALYZER");
                    assertThat(dependency.scope()).isEqualTo("automationservice-sox");
                    assertThat(dependency.laneId()).isEqualTo(ANALYZER_LANE_ID);
                    assertThat(dependency.status()).isEqualTo("COMPLETED");
                });
        assertThat(architect.execution()).isNotNull();
        assertThat(architect.execution().executionId()).isEqualTo(EXECUTION_ID);
        assertThat(architect.execution().currentStepId()).isEqualTo("architecture_direction");
        assertThat(architect.execution().currentStepOrder()).isEqualTo(2);
    }

    @Test
    void givenReviewerReady_whenGraph_thenReviewerIsVisible() {
        final Ticket ticket = Ticket.builder()
                .id(TICKET_ID)
                .ticketKey("SITIONIX-142")
                .taskDescription("task")
                .status(TicketStatus.OPEN)
                .createdAt(LocalDateTime.parse("2026-06-05T10:00:00"))
                .lanes(List.of(Lane.builder()
                        .id(REVIEWER_LANE_ID)
                        .agent(Agent.REVIEWER)
                        .scope("GLOBAL")
                        .status(LaneStatus.READY_TO_START)
                        .dependsOn(new LinkedHashSet<>())
                        .build()))
                .build();
        when(this.ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(this.laneExecutionRepository.findByTicketId(TICKET_ID)).thenReturn(List.of());
        when(this.ticketOperatorRunRepository.findByTicketId(TICKET_ID)).thenReturn(Optional.empty());

        final OperatorUiTicketGraphResponse actual = this.useCase.graph(TICKET_ID);

        assertThat(actual.lanes()).singleElement()
                .satisfies(lane -> {
                    assertThat(lane.laneId()).isEqualTo(REVIEWER_LANE_ID);
                    assertThat(lane.agent()).isEqualTo("REVIEWER");
                    assertThat(lane.status()).isEqualTo("READY_TO_START");
                });
    }

    @Test
    void givenLane_whenLaneDetail_thenReturnInputTasksStepsAndEvents() {
        final Ticket ticket = Ticket.builder()
                .id(TICKET_ID)
                .ticketKey("SITIONIX-142")
                .taskDescription("task")
                .status(TicketStatus.OPEN)
                .lanes(List.of(
                        Lane.builder()
                                .id(ANALYZER_LANE_ID)
                                .agent(Agent.ANALYZER)
                                .scope("automationservice-sox")
                                .status(LaneStatus.COMPLETED)
                                .dependsOn(new LinkedHashSet<>())
                                .build(),
                        Lane.builder()
                                .id(ARCHITECT_LANE_ID)
                                .agent(Agent.ARCHITECT)
                                .scope("automationservice-sox")
                                .serviceId("atmssox")
                                .status(LaneStatus.IN_PROGRESS)
                                .inputTaskIds(new LinkedHashSet<>(List.of(INPUT_TASK_ID)))
                                .dependsOn(new LinkedHashSet<>(List.of(LaneDependency.builder()
                                        .type(Agent.ANALYZER)
                                        .scope("automationservice-sox")
                                        .build())))
                                .build()
                ))
                .build();
        final LaneExecution execution = LaneExecution.builder()
                .id(EXECUTION_ID)
                .ticketId(TICKET_ID)
                .laneId(ARCHITECT_LANE_ID)
                .agentId("architect")
                .scope("automationservice-sox")
                .status(LaneExecutionStatus.TURN_RUNNING)
                .currentStepId("architecture_direction")
                .currentStepOrder(2)
                .currentStepTitle("Architecture Direction")
                .stderrTail(List.of("stderr line"))
                .startedAt(LocalDateTime.parse("2026-06-05T10:01:00"))
                .build();
        final AgentTicket<AgentTicketPayload> inputTask = AgentTicket.<AgentTicketPayload>builder()
                .id(INPUT_TASK_ID)
                .ticketId(TICKET_ID)
                .sourceLaneId(ANALYZER_LANE_ID)
                .laneId(ARCHITECT_LANE_ID)
                .agent(Agent.ARCHITECT)
                .scope("automationservice-sox")
                .status(AgentTicketStatus.CREATED)
                .payload(ArchitectPayload.builder()
                        .scope("automationservice-sox")
                        .requirements(Set.of("requirement"))
                        .build())
                .createdAt(LocalDateTime.parse("2026-06-05T10:00:30"))
                .build();
        when(this.ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(this.laneExecutionRepository.findByTicketId(TICKET_ID)).thenReturn(List.of(execution));
        when(this.agentTicketRepository.findByIds(anyCollection())).thenReturn(List.of(inputTask));
        when(this.laneStrategyRepository.findByAgentId("architect")).thenReturn(LaneStrategy.builder()
                .agentId("architect")
                .version(1)
                .steps(List.of(
                        LaneStrategyStep.builder().id("input_normalization").order(1).title("Input Normalization").build(),
                        LaneStrategyStep.builder().id("architecture_direction").order(2).title("Architecture Direction").build()
                ))
                .build());
        when(this.laneExecutionRepository.findStepExecutions(EXECUTION_ID)).thenReturn(List.of(LaneStepExecution.builder()
                .id(UUID.randomUUID())
                .executionId(EXECUTION_ID)
                .stepId("input_normalization")
                .stepOrder(1)
                .done(true)
                .evidenceJson("{}")
                .build()));
        when(this.ticketOperatorEventService.recentEvents(TICKET_ID)).thenReturn(List.of(
                TicketOperatorEvent.builder()
                        .ticketId(TICKET_ID)
                        .laneId(ARCHITECT_LANE_ID)
                        .eventType("ORCHESTRATOR_MESSAGE")
                        .message("prompt")
                        .stepId("architecture_direction")
                        .timestamp(Instant.parse("2026-06-05T10:02:00Z"))
                        .build(),
                TicketOperatorEvent.builder()
                        .ticketId(TICKET_ID)
                        .laneId(ANALYZER_LANE_ID)
                        .eventType("AGENT_MESSAGE")
                        .message("other lane event")
                        .stepId("scope_slicing")
                        .timestamp(Instant.parse("2026-06-05T10:01:00Z"))
                        .build()
        ));

        final OperatorUiLaneDetailResponse actual = this.useCase.lane(TICKET_ID, ARCHITECT_LANE_ID);

        assertThat(actual.laneId()).isEqualTo(ARCHITECT_LANE_ID);
        assertThat(actual.dependencies()).singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.laneId()).isEqualTo(ANALYZER_LANE_ID);
                    assertThat(dependency.status()).isEqualTo("COMPLETED");
                });
        assertThat(actual.inputTasks()).singleElement()
                .satisfies(task -> {
                    assertThat(task.sourceLaneId()).isEqualTo(ANALYZER_LANE_ID);
                    assertThat(task.sourceAgent()).isEqualTo("ANALYZER");
                    assertThat(task.payloadType()).isEqualTo("ArchitectPayload");
                    assertThat(task.payloadJson()).contains("automationservice-sox");
                });
        assertThat(actual.steps()).extracting(OperatorUiLaneStep::status)
                .containsExactly("DONE", "RUNNING");
        assertThat(actual.stderrTail()).containsExactly("stderr line");
        assertThat(actual.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.role()).isEqualTo("ORCHESTRATOR");
                    assertThat(event.message()).isEqualTo("prompt");
                });
        assertThat(actual.events()).extracting(OperatorUiLaneEvent::message)
                .doesNotContain("other lane event");
    }
}
