package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteUnitTestLaneOrchestrationUseCaseTest {

    private CompleteUnitTestLaneOrchestrationUseCase completeUnitTestLaneOrchestrationUseCase;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private CompleteAgentTasks completeAgentTasks;

    @Mock
    private LaneScopeValidator laneScopeValidator;
    @Mock
    private LaneRepository laneRepository;

    @BeforeEach
    void setUp() {
        this.completeUnitTestLaneOrchestrationUseCase = new CompleteUnitTestLaneOrchestrationUseCase(
                this.agentTicketApiMapper,
                this.completeAgentTasks,
                this.laneScopeValidator,
                this.laneRepository
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.agentTicketApiMapper, this.completeAgentTasks, this.laneScopeValidator, this.laneRepository);
    }

    @Test
    void givenValidUnitTestCompletion_whenComplete_thenCreateReviewerTicketAndCompleteLane() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteUnitTestLaneRequestDTO request = CompleteUnitTestLaneRequestDTO.builder().scope("automationservice-sox").build();
        final AgentTicket<ReviewerPayload> reviewerTicket = mock(AgentTicket.class);
        when(this.agentTicketApiMapper.asReviewerTicket(request, ticketId)).thenReturn(reviewerTicket);
        when(this.laneRepository.findProducedLanes(laneId)).thenReturn(List.of(Lane.builder().agent(Agent.REVIEWER).build()));

        //when
        this.completeUnitTestLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateUnitTestCallbackScope(laneId, request.getScope());
        verify(this.laneRepository).findProducedLanes(laneId);
        verify(this.agentTicketApiMapper).asReviewerTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.of(reviewerTicket));
    }
}
