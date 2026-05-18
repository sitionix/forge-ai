package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
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
class QaLeadAgentExecutorTest {

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private CodexClient codexClient;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private TicketRepository ticketRepository;

    private QaLeadAgentExecutor qaLeadAgentExecutor;

    @BeforeEach
    void setUp() {
        this.qaLeadAgentExecutor = new QaLeadAgentExecutor(
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
    void givenReadyToStartLane_whenExecuteLane_thenSubmitQaLeadInput() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();

        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.QA_LEAD)
                .scope("automationservice-sox")
                .serviceId("atmssox")
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

        final QaLeadPayload payload = QaLeadPayload.builder()
                .requirements(Set.of("req"))
                .build();
        final AgentTicket<QaLeadPayload> agentTicket = AgentTicket.<QaLeadPayload>builder()
                .id(inputTaskId)
                .payload(payload)
                .build();
        when(this.agentTicketRepository.findById(inputTaskId, QaLeadPayload.class)).thenReturn(Optional.of(agentTicket));

        final AgentExecutionInput<AgentTicketPayload> enrichedInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .payload(payload)
                .build();
        when(this.prepareAgentExecutionInputUseCase.enrichWithPayload(lane, baseInput, payload)).thenReturn(enrichedInput);

        //when
        this.qaLeadAgentExecutor.executeLane(lane);

        //then
        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId, QaLeadPayload.class);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithPayload(lane, baseInput, payload);

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.codexClient).submit(inputCaptor.capture(), eq("/dev/ttys004"));
        final AgentExecutionInput actual = inputCaptor.getValue();
        assertThat(actual).isEqualTo(enrichedInput);
    }
}
