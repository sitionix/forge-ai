package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeAgentExecutorTest {

    private BeAgentExecutor beAgentExecutor;

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private CodexClient codexClient;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private TicketRepository ticketRepository;

    @BeforeEach
    void setUp() {
        this.beAgentExecutor = new BeAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.codexClient,
                this.agentTicketRepository,
                this.ticketRepository
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.prepareAgentExecutionInputUseCase,
                this.codexClient,
                this.agentTicketRepository,
                this.ticketRepository
        );
    }

    @Test
    void givenReadyToStartLaneWithInputTasks_whenExecuteLane_thenSubmitEnrichedInputToCodex() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();
        final ReadyToStartLane lane = this.getLane(ticketId, laneId);
        final Lane laneState = this.getLaneState(laneId, inputTaskId);
        final ImplementBePayload payload = this.getPayload();
        final AgentTicket<ImplementBePayload> agentTicket = this.getAgentTicket(inputTaskId, payload);
        final AgentExecutionInput<AgentTicketPayload> baseInput = this.getBaseInput(ticketId, laneId);
        final AgentExecutionInput<AgentTicketPayload> enrichedInput = this.getEnrichedInput(ticketId, laneId, payload);

        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(laneState));
        when(this.agentTicketRepository.findById(inputTaskId, ImplementBePayload.class)).thenReturn(Optional.of(agentTicket));
        when(this.prepareAgentExecutionInputUseCase.enrichWithTasks(lane, baseInput, Set.of(payload))).thenReturn(enrichedInput);

        //when
        this.beAgentExecutor.executeLane(lane);

        //then
        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId, ImplementBePayload.class);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithTasks(lane, baseInput, Set.of(payload));
        verify(this.codexClient).submit(enrichedInput, "/dev/ttys004");
    }

    @Test
    void givenReadyToStartLaneWithoutInputTasks_whenExecuteLane_thenThrowIllegalStateException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final ReadyToStartLane lane = this.getLane(ticketId, laneId);
        final Lane laneState = Lane.builder()
                .id(laneId)
                .inputTaskIds(Set.of())
                .build();
        final AgentExecutionInput<AgentTicketPayload> baseInput = this.getBaseInput(ticketId, laneId);
        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(laneState));

        //when then
        assertThatThrownBy(() -> this.beAgentExecutor.executeLane(lane))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No input task ids found for laneId=" + laneId);

        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenReadyToStartLaneWithMissingInputTask_whenExecuteLane_thenThrowIllegalArgumentException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();
        final ReadyToStartLane lane = this.getLane(ticketId, laneId);
        final Lane laneState = this.getLaneState(laneId, inputTaskId);
        final AgentExecutionInput<AgentTicketPayload> baseInput = this.getBaseInput(ticketId, laneId);

        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(laneState));
        when(this.agentTicketRepository.findById(inputTaskId, ImplementBePayload.class)).thenReturn(Optional.empty());

        //when then
        assertThatThrownBy(() -> this.beAgentExecutor.executeLane(lane))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent ticket not found with id: " + inputTaskId);

        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId, ImplementBePayload.class);
    }

    private ReadyToStartLane getLane(final UUID ticketId, final UUID laneId) {
        return ReadyToStartLane.builder()
                .ticketId(ticketId)
                .ticketKey("SITIONIX-1")
                .laneId(laneId)
                .agent(Agent.IMPLEMENT_BE)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .sourceTerminalTty("/dev/ttys004")
                .build();
    }

    private Lane getLaneState(final UUID laneId, final UUID inputTaskId) {
        return Lane.builder()
                .id(laneId)
                .inputTaskIds(Set.of(inputTaskId))
                .build();
    }

    private AgentTicket<ImplementBePayload> getAgentTicket(final UUID inputTaskId, final ImplementBePayload payload) {
        return AgentTicket.<ImplementBePayload>builder()
                .id(inputTaskId)
                .payload(payload)
                .build();
    }

    private ImplementBePayload getPayload() {
        return ImplementBePayload.builder()
                .task("implement task")
                .scope("automationservice-sox")
                .summary("implement summary")
                .requirements(Set.of("r1"))
                .constraints(Set.of("c1"))
                .nonGoals(Set.of("n1"))
                .architectureDecision("decision")
                .dependencies(Set.of("d1"))
                .acceptanceNotes(Set.of("a1"))
                .risks(Set.of("risk1"))
                .build();
    }

    private AgentExecutionInput<AgentTicketPayload> getBaseInput(final UUID ticketId, final UUID laneId) {
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .build();
    }

    private AgentExecutionInput<AgentTicketPayload> getEnrichedInput(final UUID ticketId,
                                                                      final UUID laneId,
                                                                      final ImplementBePayload payload) {
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .tasks(Set.of(payload))
                .build();
    }
}
