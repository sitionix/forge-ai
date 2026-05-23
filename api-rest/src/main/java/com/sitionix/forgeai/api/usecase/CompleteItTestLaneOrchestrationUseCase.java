package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteItTestLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final AgentTicketRepository agentTicketRepository;
    private final CompleteAgentLane completeAgentLane;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteItTestLaneRequestDTO request) {
        this.laneScopeValidator.validateItTestCompletion(ticketId, laneId, request.getScope());
        final AgentTicket<TestItCompletionPayload> completionReport = this.agentTicketApiMapper.asTestItCompletionTicket(request, ticketId, laneId);

        this.agentTicketRepository.save(completionReport);
        this.completeAgentLane.completeAndPrepareAgents(laneId);
    }
}
