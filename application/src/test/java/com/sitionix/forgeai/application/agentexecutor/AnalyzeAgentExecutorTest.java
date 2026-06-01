package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.AnalyzerExecutionPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.repository.TicketRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeAgentExecutorTest {

    private AnalyzeAgentExecutor analyzeAgentExecutor;

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private CodexClient codexClient;

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private SupervisedExecutionProperties supervisedExecutionProperties;
    @Mock
    private SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    @BeforeEach
    void setUp() {
        this.analyzeAgentExecutor = new AnalyzeAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.codexClient,
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
                this.ticketRepository,
                this.supervisedExecutionProperties,
                this.supervisedLaneExecutionUseCase
        );
    }

    @Test
    void givenReadyLane_whenExecute_thenSubmitEnrichedInputToCodex() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.ANALYZER)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .sourceTerminalTty("/dev/ttys003")
                .build();

        final AgentExecutionInput<AgentTicketPayload> baseInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agentInstruction("instruction")
                .sharedInstructions(Set.of("shared"))
                .build();
        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);

        when(this.ticketRepository.findTicketContentById(ticketId)).thenReturn("task-description");
        final AgentExecutionInput<AgentTicketPayload> enrichedInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .tasks(Set.of(AnalyzerExecutionPayload.builder().ticket("task-description").build()))
                .build();
        when(this.prepareAgentExecutionInputUseCase.enrichWithTasks(eq(lane), eq(baseInput), any(Set.class)))
                .thenReturn(enrichedInput);
        when(this.supervisedExecutionProperties.isSupervisedAgent("analyzer")).thenReturn(false);

        //when
        this.analyzeAgentExecutor.executeLane(lane);

        //then
        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.ticketRepository).findTicketContentById(ticketId);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithTasks(eq(lane), eq(baseInput), any(Set.class));
        verify(this.supervisedExecutionProperties).isSupervisedAgent("analyzer");

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.codexClient).submit(inputCaptor.capture(), eq("/dev/ttys003"));
        final AgentExecutionInput actual = inputCaptor.getValue();

        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isEqualTo(laneId);
        assertThat(actual.getTasks()).isEqualTo(Set.of(AnalyzerExecutionPayload.builder()
                .ticket("task-description")
                .build()));
    }

    @Test
    void givenSupervisedEnabled_whenExecute_thenUseSupervisor() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.ANALYZER)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .sourceTerminalTty("/dev/ttys003")
                .build();

        final AgentExecutionInput<AgentTicketPayload> baseInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .build();
        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);
        when(this.ticketRepository.findTicketContentById(ticketId)).thenReturn("task-description");

        final AnalyzerExecutionPayload payload = AnalyzerExecutionPayload.builder().ticket("task-description").build();
        final AgentExecutionInput<AgentTicketPayload> enrichedInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .tasks(Set.of(payload))
                .build();
        when(this.prepareAgentExecutionInputUseCase.enrichWithTasks(eq(lane), eq(baseInput), any(Set.class)))
                .thenReturn(enrichedInput);
        when(this.supervisedExecutionProperties.isSupervisedAgent("analyzer")).thenReturn(true);
        when(this.supervisedExecutionProperties.getCorrectionAttempts()).thenReturn(2);

        this.analyzeAgentExecutor.executeLane(lane);

        verify(this.supervisedExecutionProperties).isSupervisedAgent("analyzer");
        verify(this.supervisedExecutionProperties).getCorrectionAttempts();
        verify(this.supervisedLaneExecutionUseCase).execute(lane, enrichedInput, 2);
    }
}
