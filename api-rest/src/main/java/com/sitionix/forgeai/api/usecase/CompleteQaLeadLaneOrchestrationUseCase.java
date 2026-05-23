package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;

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
        this.routeUiLane(laneId, request);
    }

    private void routeUiLane(final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        if (!this.hasProducedLane(laneId, request.getScope(), Agent.TEST_UI)) {
            return;
        }
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getUiTestRequired())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "QA lead test lane routing is not supported yet: agent=" + Agent.TEST_UI + ", scope=" + request.getScope());
        }
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_UI);
    }

    private boolean hasProducedLane(final UUID laneId, final String scope, final Agent agent) {
        final var lane = this.laneRepository.findLaneToProduceOptional(laneId, scope, agent);
        return lane != null && lane.isPresent();
    }

    private <P extends AgentTicketPayload> void routeTestLane(final UUID laneId,
                                                               final String scope,
                                                               final boolean required,
                                                               final Agent agent,
                                                               final Supplier<AgentTicket<P>> ticketSupplier) {
        if (!this.hasProducedLane(laneId, scope, agent)) {
            return;
        }
        if (required) {
            this.createAgentTask.create(ticketSupplier.get(), laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, scope, agent);
    }
}
