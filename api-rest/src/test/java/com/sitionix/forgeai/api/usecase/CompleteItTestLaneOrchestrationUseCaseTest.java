package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteItTestLaneOrchestrationUseCaseTest {

    private CompleteItTestLaneOrchestrationUseCase completeItTestLaneOrchestrationUseCase;

    @Mock
    private LaneScopeValidator laneScopeValidator;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private CompleteAgentLane completeAgentLane;

    @BeforeEach
    void setUp() {
        this.completeItTestLaneOrchestrationUseCase = new CompleteItTestLaneOrchestrationUseCase(
                this.laneScopeValidator,
                this.agentTicketApiMapper,
                this.agentTicketRepository,
                this.completeAgentLane
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.laneScopeValidator, this.agentTicketApiMapper, this.agentTicketRepository, this.completeAgentLane);
    }

    @Test
    void givenValidItCompletion_whenComplete_thenStoreReportAndCompleteLane() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteItTestLaneRequestDTO request = mock(CompleteItTestLaneRequestDTO.class);
        final AgentTicket<TestItCompletionPayload> completionReport = mock(AgentTicket.class);
        when(request.getScope()).thenReturn("automationservice-sox");
        when(this.agentTicketApiMapper.asTestItCompletionTicket(request, ticketId, laneId)).thenReturn(completionReport);

        //when
        this.completeItTestLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateItTestCompletion(ticketId, laneId, "automationservice-sox");
        verify(this.agentTicketApiMapper).asTestItCompletionTicket(request, ticketId, laneId);
        final ArgumentCaptor<AgentTicket<TestItCompletionPayload>> reportCaptor = ArgumentCaptor.forClass(AgentTicket.class);
        verify(this.agentTicketRepository).save(reportCaptor.capture());
        verify(this.completeAgentLane).completeAndPrepareAgents(laneId);
        assertThat(reportCaptor.getValue()).isEqualTo(completionReport);
    }
}
