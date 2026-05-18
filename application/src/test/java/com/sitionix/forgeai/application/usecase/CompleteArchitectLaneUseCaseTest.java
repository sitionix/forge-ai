package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.CompleteArchitectLaneCommand;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
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
class CompleteArchitectLaneUseCaseTest {

    @Mock
    private CreateAgentTask createAgentTask;

    private CompleteArchitectLaneUseCase completeArchitectLaneUseCase;

    @BeforeEach
    void setUp() {
        this.completeArchitectLaneUseCase = new CompleteArchitectLaneUseCase(this.createAgentTask);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.createAgentTask);
    }

    @Test
    void givenRequiredRequests_whenComplete_thenCreateAllAgentTasks() {
        //given
        final UUID sourceLaneId = UUID.randomUUID();
        final AgentTicket<ImplementBePayload> implementBeTicket = AgentTicket.<ImplementBePayload>builder().agent(Agent.IMPLEMENT_BE).build();
        final AgentTicket<ApiPayload> apiTicket = AgentTicket.<ApiPayload>builder().agent(Agent.API).build();
        final AgentTicket<EventPayload> eventTicket = AgentTicket.<EventPayload>builder().agent(Agent.EVENT).build();
        final CompleteArchitectLaneCommand command = CompleteArchitectLaneCommand.builder()
                .sourceLaneId(sourceLaneId)
                .implementBeTicket(implementBeTicket)
                .apiTicket(apiTicket)
                .eventTicket(eventTicket)
                .apiRequired(Boolean.TRUE)
                .eventRequired(Boolean.TRUE)
                .build();

        //when
        this.completeArchitectLaneUseCase.complete(command);

        //then
        verify(this.createAgentTask).create(implementBeTicket, sourceLaneId);
        verify(this.createAgentTask).create(apiTicket, sourceLaneId);
        verify(this.createAgentTask).create(eventTicket, sourceLaneId);
    }

    @Test
    void givenOptionalRequestsDisabled_whenComplete_thenMarkAsNotNeeded() {
        //given
        final UUID sourceLaneId = UUID.randomUUID();
        final AgentTicket<ImplementFePayload> implementFeTicket = AgentTicket.<ImplementFePayload>builder().build();
        final CompleteArchitectLaneCommand command = CompleteArchitectLaneCommand.builder()
                .sourceLaneId(sourceLaneId)
                .implementFeTicket(implementFeTicket)
                .apiRequired(Boolean.FALSE)
                .apiScope("GLOBAL")
                .eventRequired(Boolean.FALSE)
                .eventScope("GLOBAL")
                .build();

        //when
        this.completeArchitectLaneUseCase.complete(command);

        //then
        verify(this.createAgentTask).create(implementFeTicket, sourceLaneId);
        verify(this.createAgentTask).markAsNotNeeded(sourceLaneId, "GLOBAL", Agent.API);
        verify(this.createAgentTask).markAsNotNeeded(sourceLaneId, "GLOBAL", Agent.EVENT);
    }
}
