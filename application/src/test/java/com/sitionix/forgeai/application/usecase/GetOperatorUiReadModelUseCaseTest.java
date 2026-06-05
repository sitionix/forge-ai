package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetOperatorUiReadModelUseCaseTest {

    private static final UUID TICKET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ANALYZER_LANE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARCHITECT_LANE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EXECUTION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private GetOperatorUiReadModel useCase;

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private LaneExecutionRepository laneExecutionRepository;
    @Mock
    private TicketOperatorRunRepository ticketOperatorRunRepository;

    @BeforeEach
    void setUp() {
        this.useCase = new GetOperatorUiReadModelUseCase(
                this.ticketRepository,
                this.laneExecutionRepository,
                this.ticketOperatorRunRepository
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

        final GetOperatorUiReadModel.OperatorUiTicketGraphResponse actual = this.useCase.graph(TICKET_ID);

        assertThat(actual.ticketId()).isEqualTo(TICKET_ID);
        assertThat(actual.lanes()).hasSize(2);
        assertThat(actual.laneCounts().completed()).isEqualTo(1);
        assertThat(actual.laneCounts().inProgress()).isEqualTo(1);
        final GetOperatorUiReadModel.OperatorUiLaneNode architect = actual.lanes().get(1);
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
}
