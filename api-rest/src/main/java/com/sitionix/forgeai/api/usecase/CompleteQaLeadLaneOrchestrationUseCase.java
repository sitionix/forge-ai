package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.sitionix.forgeai.api.LaneCompletionValidator;
import com.sitionix.forgeai.api.RequestValidationException;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteQaLeadLaneOrchestrationUseCase {

    private final LaneCompletionValidator laneCompletionValidator;
    private final CreateAgentTask createAgentTask;
    private final AgentTicketApiMapper agentTicketApiMapper;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.laneCompletionValidator.validateQaLeadCompletion(ticketId, laneId, request.getScope());
        this.validateRequest(request);
        this.routeTestLanes(ticketId, laneId, request);
    }

    private void routeTestLanes(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.routeUnitLane(ticketId, laneId, request);
        this.routeIntegrationLane(ticketId, laneId, request);
    }

    private void routeUnitLane(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired())) {
            final AgentTicket<TestUnitPayload> testUnitTicket = this.agentTicketApiMapper.asTestUnitTicket(request, ticketId);
            this.createAgentTask.create(testUnitTicket, laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_UNIT);
    }

    private void routeIntegrationLane(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired())) {
            final AgentTicket<TestItPayload> testItTicket = this.agentTicketApiMapper.asTestItTicket(request, ticketId);
            this.createAgentTask.create(testItTicket, laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_IT);
    }

    private void validateRequest(final CompleteQaLeadLaneRequestDTO request) {
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired())
                && (request.getIntegrationTestCases() == null || request.getIntegrationTestCases().isEmpty())) {
            throw new RequestValidationException("integrationTestCases must not be empty when integrationTestRequired is true");
        }
    }
}
