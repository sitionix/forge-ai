package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadUnitTestNote;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeAffectedSurface;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
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
class TestUiAgentExecutorTest {

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    private final SupervisedExecutionProperties supervisedExecutionProperties = new SupervisedExecutionProperties();
    private TestUiAgentExecutor testUiAgentExecutor;

    @BeforeEach
    void setUp() {
        this.supervisedExecutionProperties.setCorrectionAttempts(2);
        this.testUiAgentExecutor = new TestUiAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.agentTicketRepository,
                this.ticketRepository,
                this.supervisedLaneExecutionUseCase,
                this.supervisedExecutionProperties
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
                .agent(Agent.TEST_UI)
                .scope("sitionix-spa")
                .serviceId("sitionix-spa")
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

        final TestUiPayload payload = new TestUiPayload(
                "task",
                "sitionix-spa",
                "summary",
                Set.of(new ImplementFeChangedFile("src/main.ts", "reason")),
                Set.of(new ImplementFeAffectedSurface("surface", "surface", "reason")),
                Set.of("ui behavior"),
                new UnitTestSonar(null, 1),
                Set.of(new QaLeadUnitTestNote("note", "detail"))
        );
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

        this.testUiAgentExecutor.executeLane(lane);

        verify(this.prepareAgentExecutionInputUseCase).executeClaimed(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithTasks(lane, baseInput, Set.of(payload));

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.supervisedLaneExecutionUseCase).execute(eq(lane), inputCaptor.capture(), eq(2));
        assertThat(inputCaptor.getValue()).isEqualTo(enrichedInput);
    }
}
