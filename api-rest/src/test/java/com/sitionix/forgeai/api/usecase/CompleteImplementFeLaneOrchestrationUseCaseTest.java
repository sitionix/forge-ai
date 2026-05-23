package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeCompletionPayload;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.List;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
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
class CompleteImplementFeLaneOrchestrationUseCaseTest {

    private CompleteImplementFeLaneOrchestrationUseCase completeImplementFeLaneOrchestrationUseCase;

    @Mock
    private LaneScopeValidator laneScopeValidator;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private CompleteAgentTasks completeAgentTasks;

    @BeforeEach
    void setUp() {
        this.completeImplementFeLaneOrchestrationUseCase = new CompleteImplementFeLaneOrchestrationUseCase(
                this.laneScopeValidator,
                this.agentTicketApiMapper,
                this.agentTicketRepository,
                this.completeAgentTasks
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.laneScopeValidator,
                this.agentTicketApiMapper,
                this.agentTicketRepository,
                this.completeAgentTasks
        );
    }

    @Test
    void givenFrontendScope_whenComplete_thenStoreReportAndCompleteLane() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementFeLaneRequestDTO request = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend changes for assigned flow.")
                .build();
        final AgentTicket<ImplementFeCompletionPayload> completionReport = mock(AgentTicket.class);
        when(this.agentTicketApiMapper.asImplementFeCompletionTicket(request, ticketId, laneId)).thenReturn(completionReport);

        //when
        this.completeImplementFeLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateImplementFeCompletion(ticketId, laneId, "sitionix-spa");
        verify(this.agentTicketApiMapper).asImplementFeCompletionTicket(request, ticketId, laneId);
        final ArgumentCaptor<AgentTicket<ImplementFeCompletionPayload>> reportCaptor = ArgumentCaptor.forClass(AgentTicket.class);
        verify(this.agentTicketRepository).save(reportCaptor.capture());
        verify(this.completeAgentTasks).complete(laneId, List.of());
        assertThat(reportCaptor.getValue()).isEqualTo(completionReport);
    }
}
