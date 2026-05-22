package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class CompleteItTestLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final AgentTicketRepository agentTicketRepository;
    private final CompleteAgentLane completeAgentLane;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteItTestLaneRequestDTO request) {
        this.validateCoveredCases(request.getCoveredCases());
        this.laneScopeValidator.validateItTestCompletion(ticketId, laneId, request.getScope());
        final AgentTicket<TestItCompletionPayload> completionReport = this.agentTicketApiMapper.asTestItCompletionTicket(request, ticketId, laneId);

        this.agentTicketRepository.save(completionReport);
        this.completeAgentLane.completeAndPrepareAgents(laneId);
    }

    private void validateCoveredCases(final List<String> coveredCases) {
        if (coveredCases == null || coveredCases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "coveredCases must not be empty");
        }
        if (coveredCases.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "coveredCases must not contain blank values");
        }
    }
}
