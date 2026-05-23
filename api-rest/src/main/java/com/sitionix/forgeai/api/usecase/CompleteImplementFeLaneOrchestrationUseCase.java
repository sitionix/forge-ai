package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeCompletionPayload;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteImplementFeLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final AgentTicketRepository agentTicketRepository;
    private final CompleteAgentTasks completeAgentTasks;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteImplementFeLaneRequestDTO request) {
        this.laneScopeValidator.validateImplementFeCompletion(ticketId, laneId, request.getScope());

        final AgentTicket<ImplementFeCompletionPayload> completionReport =
                this.agentTicketApiMapper.asImplementFeCompletionTicket(request, ticketId, laneId);
        this.agentTicketRepository.save(completionReport);
        this.completeAgentTasks.complete(laneId, List.of());
    }
}
