package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteQaLeadLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final CreateAgentTask createAgentTask;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final LaneRepository laneRepository;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.laneScopeValidator.validateQaLeadCompletion(ticketId, laneId, request.getScope());
        this.routeTestLanes(ticketId, laneId, request);
    }

    private void routeTestLanes(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.routeTestLane(
                laneId,
                request.getScope(),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired()),
                Agent.TEST_UNIT,
                () -> this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)
        );
        this.routeTestLane(
                laneId,
                request.getScope(),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired()),
                Agent.TEST_IT,
                () -> this.agentTicketApiMapper.asTestItTicket(request, ticketId)
        );
        this.routeTestLane(
                laneId,
                request.getScope(),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getUiTestRequired()),
                Agent.TEST_UI,
                () -> this.agentTicketApiMapper.asTestUiTicket(request, ticketId)
        );
    }

    private <P extends AgentTicketPayload> void routeTestLane(final UUID laneId,
                                                               final String scope,
                                                               final boolean required,
                                                               final Agent agent,
                                                               final Supplier<AgentTicket<P>> ticketSupplier) {
        final Optional<?> lane = this.laneRepository.findLaneToProduceOptional(laneId, scope, agent);
        if (lane.isEmpty()) {
            return;
        }
        if (required) {
            this.createAgentTask.create(ticketSupplier.get(), laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, scope, agent);
    }
}
