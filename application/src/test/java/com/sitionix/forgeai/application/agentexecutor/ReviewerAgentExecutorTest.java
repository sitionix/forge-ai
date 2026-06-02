package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewerAgentExecutorTest {

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;
    @Mock
    private AgentTicketRepository agentTicketRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    @Test
    void givenReadyToStartLane_whenExecuteLane_thenUseSupervisedRuntime() {
        final ReviewerAgentExecutor reviewerAgentExecutor = new ReviewerAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.agentTicketRepository,
                this.ticketRepository,
                this.supervisedLaneExecutionUseCase,
                new SupervisedExecutionProperties()
        );
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.REVIEWER)
                .scope("GLOBAL")
                .serviceId("global")
                .sourceTerminalTty("/dev/ttys004")
                .build();
        final AgentExecutionInput<AgentTicketPayload> baseInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .build();
        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(Lane.builder().id(laneId).inputTaskIds(Set.of(inputTaskId)).build()));
        final ReviewerPayload payload = new ReviewerPayload("review", "GLOBAL", "summary", List.of("file"), null);
        when(this.agentTicketRepository.findById(inputTaskId)).thenReturn(Optional.of(AgentTicket.<AgentTicketPayload>builder().id(inputTaskId).payload(payload).build()));
        final AgentExecutionInput<AgentTicketPayload> enrichedInput = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .tasks(Set.of(payload))
                .build();
        when(this.prepareAgentExecutionInputUseCase.enrichWithTasks(lane, baseInput, Set.of(payload))).thenReturn(enrichedInput);

        reviewerAgentExecutor.executeLane(lane);

        final ArgumentCaptor<AgentExecutionInput<AgentTicketPayload>> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.supervisedLaneExecutionUseCase).execute(org.mockito.Mockito.eq(lane), inputCaptor.capture(), org.mockito.Mockito.anyInt());
        assertThat(inputCaptor.getValue()).isEqualTo(enrichedInput);
    }
}
