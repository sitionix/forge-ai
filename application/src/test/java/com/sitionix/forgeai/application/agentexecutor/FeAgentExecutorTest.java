package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeAgentExecutorTest {

    private FeAgentExecutor feAgentExecutor;

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
        this.feAgentExecutor = new FeAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.codexClient,
                this.agentTicketRepository,
                this.ticketRepository
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.prepareAgentExecutionInputUseCase, this.codexClient, this.agentTicketRepository, this.ticketRepository);
    }

    @Test
    void givenReadyToStartLane_whenExecuteLane_thenSubmitImplementFeInput() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();

        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.IMPLEMENT_FE)
                .scope("sitionix-spa")
                .serviceId("sitionix-spa")
                .sourceTerminalTty("/dev/ttys004")
                .build();

        final AgentExecutionInput<AgentTicketPayload> baseInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .build();
        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);

        final Lane laneState = Lane.builder()
                .id(laneId)
                .inputTaskIds(Set.of(inputTaskId))
                .build();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(laneState));

        final ImplementFePayload payload = ImplementFePayload.builder()
                .task("implement ui task")
                .scope("sitionix-spa")
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

        //when
        this.feAgentExecutor.executeLane(lane);

        //then
        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithTasks(lane, baseInput, Set.of(payload));

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.codexClient).submit(inputCaptor.capture(), eq("/dev/ttys004"));
        assertThat(inputCaptor.getValue()).isEqualTo(enrichedInput);
    }
}
