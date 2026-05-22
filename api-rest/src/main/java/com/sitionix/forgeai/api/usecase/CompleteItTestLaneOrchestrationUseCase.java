package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.sitionix.forgeai.api.LaneCompletionValidator;
import com.sitionix.forgeai.api.RequestValidationException;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteItTestLaneOrchestrationUseCase {

    private final LaneCompletionValidator laneCompletionValidator;
    private final AgentTicketRepository agentTicketRepository;
    private final CompleteAgentLane completeAgentLane;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteItTestLaneRequestDTO request) {
        this.laneCompletionValidator.validateItTestCompletion(ticketId, laneId, request.getScope());
        this.validateCoveredCases(request.getCoveredCases());

        final AgentTicket<TestItCompletionPayload> completionReport = AgentTicket.<TestItCompletionPayload>builder()
                .id(UUID.randomUUID())
                .ticketId(ticketId)
                .laneId(laneId)
                .status(AgentTicketStatus.CONSUMED)
                .scope(request.getScope())
                .agent(Agent.TEST_IT)
                .payload(TestItCompletionPayload.builder()
                        .scope(request.getScope())
                        .summary(request.getSummary())
                        .coveredCases(List.copyOf(request.getCoveredCases()))
                        .build())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        this.agentTicketRepository.save(completionReport);
        this.completeAgentLane.completeAndPrepareAgents(laneId);
    }

    private void validateCoveredCases(final List<String> coveredCases) {
        if (coveredCases == null || coveredCases.isEmpty()) {
            throw new RequestValidationException("coveredCases must not be empty");
        }
        if (coveredCases.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new RequestValidationException("coveredCases must not contain blank values");
        }
    }
}
