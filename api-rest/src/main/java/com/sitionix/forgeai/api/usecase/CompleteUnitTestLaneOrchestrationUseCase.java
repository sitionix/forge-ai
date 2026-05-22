package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteUnitTestLaneOrchestrationUseCase {

    private final AgentTicketApiMapper agentTicketApiMapper;
    private final CompleteAgentTasks completeAgentTasks;
    private final LaneScopeValidator laneScopeValidator;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteUnitTestLaneRequestDTO request) {
        this.laneScopeValidator.validateUnitTestCallbackScope(laneId, request.getScope());
        final AgentTicket<ReviewerPayload> reviewerTicket = this.agentTicketApiMapper.asReviewerTicket(request, ticketId);
        this.completeAgentTasks.complete(laneId, List.of(reviewerTicket));
    }
}
