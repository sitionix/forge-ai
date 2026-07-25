package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadDataCheck;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadIntegrationFlow;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBeIntegrationFlow;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePersistenceChange;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadIntegrationTestCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadUnitTestNote;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
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
class TestItAgentExecutorTest {

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    private final SupervisedExecutionProperties supervisedExecutionProperties = new SupervisedExecutionProperties();
    private TestItAgentExecutor testItAgentExecutor;

    @BeforeEach
    void setUp() {
        this.supervisedExecutionProperties.setCorrectionAttempts(2);
        this.testItAgentExecutor = new TestItAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.agentTicketRepository,
                this.ticketRepository,
                this.supervisedLaneExecutionUseCase,
                this.supervisedExecutionProperties,
                mock(LaneCompletionSupport.class),
                mock(CompleteAgentTasks.class),
                mock(CompleteAgentLane.class)
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
                .agent(Agent.TEST_IT)
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

        final TestItPayload payload = new TestItPayload(
                "task",
                "automationservice-sox",
                "summary",
                Set.of(new ImplementBeIntegrationFlow("flow", "GET", "/health", "health_check", "summary")),
                Set.of(new ImplementBePersistenceChange("type", "change", "summary")),
                new UnitTestSonar(null, 1),
                Set.of(new QaLeadIntegrationTestCase(
                        "case",
                        new QaLeadIntegrationFlow("flow", "GET", "/health", "health_check"),
                        Set.of("given"),
                        Set.of("when"),
                        Set.of("then"),
                        Set.of(new QaLeadDataCheck("target", "expected")),
                        "P1"
                )),
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

        this.testItAgentExecutor.executeLane(lane);

        verify(this.prepareAgentExecutionInputUseCase).executeClaimed(lane);
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.agentTicketRepository).findById(inputTaskId);
        verify(this.prepareAgentExecutionInputUseCase).enrichWithTasks(lane, baseInput, Set.of(payload));

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.supervisedLaneExecutionUseCase).execute(eq(lane), inputCaptor.capture(), eq(2));
        assertThat(inputCaptor.getValue()).isEqualTo(enrichedInput);
    }
}
