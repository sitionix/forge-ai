package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
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
class ApiAgentExecutorTest {

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private CodexClient codexClient;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private SupervisedExecutionProperties supervisedExecutionProperties;

    @Mock
    private SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    private ApiAgentExecutor apiAgentExecutor;

    @BeforeEach
    void setUp() {
        this.apiAgentExecutor = new ApiAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.codexClient,
                this.agentTicketRepository,
                this.ticketRepository,
                this.supervisedExecutionProperties,
                this.supervisedLaneExecutionUseCase
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.prepareAgentExecutionInputUseCase,
                this.codexClient,
                this.agentTicketRepository,
                this.ticketRepository,
                this.supervisedExecutionProperties,
                this.supervisedLaneExecutionUseCase
        );
    }

    @Test
    void givenReadyToStartLane_whenExecuteLane_thenSubmitApiInput() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();

        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.API)
                .scope("GLOBAL")
                .serviceId("global")
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

        final ApiPayload payload = ApiPayload.builder()
                .required(Boolean.TRUE)
                .summary("summary")
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
        when(this.supervisedExecutionProperties.isSupervisedAgent("api")).thenReturn(false);

        //when
        this.apiAgentExecutor.executeLane(lane);

        //then
        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithTasks(lane, baseInput, Set.of(payload));

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.codexClient).submit(inputCaptor.capture(), eq("/dev/ttys004"));
        final AgentExecutionInput actual = inputCaptor.getValue();
        assertThat(actual).isEqualTo(enrichedInput);
    }

    @Test
    void givenSupervisedEnabledForApi_whenExecuteLane_thenUseSupervisor() {
        // given
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .agent(Agent.API)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .sourceTerminalTty("/dev/ttys004")
                .build();
        when(this.supervisedExecutionProperties.isSupervisedAgent("api")).thenReturn(true);
        when(this.supervisedExecutionProperties.getCorrectionAttempts()).thenReturn(2);

        // when
        this.apiAgentExecutor.executeLane(lane);

        // then
        verify(this.supervisedExecutionProperties).isSupervisedAgent("api");
        verify(this.supervisedExecutionProperties).getCorrectionAttempts();
        verify(this.supervisedLaneExecutionUseCase).execute(lane, 2);
    }
}
