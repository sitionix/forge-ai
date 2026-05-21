package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
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

@ExtendWith(MockitoExtension.class)
class CompleteAgentTasksUseCaseTest {

    @Mock
    private CreateAgentTask createAgentTask;

    private CompleteAgentTasksUseCase completeAgentTasksUseCase;

    @BeforeEach
    void setUp() {
        this.completeAgentTasksUseCase = new CompleteAgentTasksUseCase(this.createAgentTask);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.createAgentTask);
    }

    @Test
    void givenAgentTickets_whenComplete_thenCreateEachTask() {
        //given
        final UUID sourceLaneId = UUID.randomUUID();
        final AgentTicket<ArchitectPayload> architectTicket = AgentTicket.<ArchitectPayload>builder().id(UUID.randomUUID()).build();
        final AgentTicket<QaLeadPayload> qaLeadTicket = AgentTicket.<QaLeadPayload>builder().id(UUID.randomUUID()).build();

        //when
        this.completeAgentTasksUseCase.complete(sourceLaneId, List.<AgentTicket<?>>of(architectTicket, qaLeadTicket));

        //then
        verify(this.createAgentTask).create(architectTicket, sourceLaneId);
        verify(this.createAgentTask).create(qaLeadTicket, sourceLaneId);
    }
}
