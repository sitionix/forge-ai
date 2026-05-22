package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneCompletionValidator {

    private final TicketRepository ticketRepository;

    public Lane validateQaLeadCompletion(final UUID ticketId, final UUID laneId, final String scope) {
        return this.validateCompletion(ticketId, laneId, scope, Agent.QA_LEAD, "QA lead");
    }

    public Lane validateItTestCompletion(final UUID ticketId, final UUID laneId, final String scope) {
        return this.validateCompletion(ticketId, laneId, scope, Agent.TEST_IT, "IT test");
    }

    private Lane validateCompletion(final UUID ticketId,
                                    final UUID laneId,
                                    final String scope,
                                    final Agent expectedAgent,
                                    final String laneLabel) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found with ticketId=" + ticketId));
        if (ticket.getLanes() == null || ticket.getLanes().stream().noneMatch(value -> Objects.equals(value.getId(), laneId))) {
            throw new LaneNotFoundException(laneLabel + " lane not found for ticketId=" + ticketId + ", laneId=" + laneId);
        }

        final Lane lane = this.ticketRepository.findByLaneId(laneId)
                .orElseThrow(() -> new LaneNotFoundException(laneLabel + " lane not found for laneId=" + laneId));
        if (!Objects.equals(lane.getAgent(), expectedAgent)) {
            throw new LaneConflictException(laneLabel + " lane type mismatch: laneId=" + laneId
                    + ", laneAgent=" + lane.getAgent()
                    + ", expectedAgent=" + expectedAgent);
        }
        if (!Objects.equals(lane.getScope(), scope)) {
            throw new LaneConflictException(laneLabel + " scope mismatch: laneId=" + laneId
                    + ", laneScope=" + lane.getScope()
                    + ", requestScope=" + scope);
        }
        if (!Objects.equals(lane.getStatus(), LaneStatus.IN_PROGRESS)) {
            throw new LaneConflictException(laneLabel + " lane cannot be completed in current state: laneId=" + laneId
                    + ", laneStatus=" + lane.getStatus());
        }
        return lane;
    }
}
