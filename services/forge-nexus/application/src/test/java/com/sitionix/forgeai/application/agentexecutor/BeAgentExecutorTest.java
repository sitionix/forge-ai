package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeAgentExecutorTest {

    private BeAgentExecutor beAgentExecutor;

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    private final SupervisedExecutionProperties supervisedExecutionProperties = new SupervisedExecutionProperties();

    @BeforeEach
    void setUp() {
        this.supervisedExecutionProperties.setCorrectionAttempts(2);
        this.beAgentExecutor = new BeAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.agentTicketRepository,
                this.ticketRepository,
                this.supervisedLaneExecutionUseCase,
                this.supervisedExecutionProperties,
                mock(LaneCompletionSupport.class),
                mock(CompleteAgentTasks.class)
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.prepareAgentExecutionInputUseCase,
                this.agentTicketRepository,
                this.ticketRepository,
                this.supervisedLaneExecutionUseCase
        );
    }

    @Test
    void givenReadyToStartLane_whenExecuteLane_thenUseSupervisorWithPreparedInput() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();

        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.IMPLEMENT_BE)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .sourceTerminalTty("/dev/ttys004")
                .build();

        final AgentExecutionInput<AgentTicketPayload> baseInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .build();
        when(this.prepareAgentExecutionInputUseCase.executeClaimed(lane)).thenReturn(baseInput);

        final Lane laneState = Lane.builder()
                .id(laneId)
                .inputTaskIds(Set.of(inputTaskId))
                .build();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(laneState));

        final ImplementBePayload payload = ImplementBePayload.builder()
                .task("implement task")
                .scope("automationservice-sox")
                .summary("summary")
                .requirements(Set.of("r1"))
                .constraints(Set.of("c1"))
                .nonGoals(Set.of("n1"))
                .architectureDecision("decision")
                .dependencies(Set.of("d1"))
                .acceptanceNotes(Set.of("a1"))
                .risks(Set.of("risk1"))
                .build();
        final AgentTicket<AgentTicketPayload> agentTicket = AgentTicket.<AgentTicketPayload>builder()
                .id(inputTaskId)
                .payload(payload)
                .build();
        when(this.agentTicketRepository.findById(inputTaskId)).thenReturn(Optional.of(agentTicket));

        final AgentExecutionInput<AgentTicketPayload> enrichedInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .tasks(Set.of(payload))
                .build();
        when(this.prepareAgentExecutionInputUseCase.enrichWithTasks(lane, baseInput, Set.of(payload))).thenReturn(enrichedInput);

        this.beAgentExecutor.executeLane(lane);

        verify(this.prepareAgentExecutionInputUseCase).executeClaimed(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithTasks(lane, baseInput, Set.of(payload));

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.supervisedLaneExecutionUseCase).execute(eq(lane), inputCaptor.capture(), eq(2));
        assertThat(inputCaptor.getValue()).isEqualTo(enrichedInput);
    }
}
