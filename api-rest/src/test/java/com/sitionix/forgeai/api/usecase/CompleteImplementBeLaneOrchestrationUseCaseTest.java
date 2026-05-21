package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteImplementBeLaneOrchestrationUseCaseTest {

    private CompleteImplementBeLaneOrchestrationUseCase completeImplementBeLaneOrchestrationUseCase;

    @Mock
    private LaneScopeValidator laneScopeValidator;

    @Mock
    private CreateAgentTask createAgentTask;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @BeforeEach
    void setUp() {
        this.completeImplementBeLaneOrchestrationUseCase = new CompleteImplementBeLaneOrchestrationUseCase(
                this.laneScopeValidator,
                this.createAgentTask,
                this.agentTicketApiMapper
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.laneScopeValidator, this.createAgentTask, this.agentTicketApiMapper);
    }

    @Test
    void givenImplementBeCompleteRequest_whenComplete_thenCreateTestUnitAndTestItTasks() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementBeLaneRequestDTO request = CompleteImplementBeLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Implement create endpoint")
                .build();
        final AgentTicket<TestUnitPayload> testUnitTicket = AgentTicket.<TestUnitPayload>builder().id(UUID.randomUUID()).build();
        final AgentTicket<TestItPayload> testItTicket = AgentTicket.<TestItPayload>builder().id(UUID.randomUUID()).build();
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        final ArgumentCaptor<AgentTicket> createdTicketCaptor = ArgumentCaptor.forClass(AgentTicket.class);

        //when
        this.completeImplementBeLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateImplementBeCallbackScope(laneId, "automationservice-sox");
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.createAgentTask, times(2)).create(createdTicketCaptor.capture(), eq(laneId));

        final List<AgentTicket> createdTickets = createdTicketCaptor.getAllValues();
        assertThat(createdTickets).hasSize(2);
        assertThat(createdTickets).containsExactly(testUnitTicket, testItTicket);
    }
}
