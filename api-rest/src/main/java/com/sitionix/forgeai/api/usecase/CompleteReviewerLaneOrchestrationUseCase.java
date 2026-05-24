package com.sitionix.forgeai.api.usecase;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class CompleteReviewerLaneOrchestrationUseCase {

    private final TicketRepository ticketRepository;
    private final CompleteAgentTasks completeAgentTasks;

    public UUID complete(final UUID ticketId) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Reviewer ticket not found with ticketId=" + ticketId));

        final List<Lane> reviewerLanes = (ticket.getLanes() == null ? Collections.<Lane>emptyList() : ticket.getLanes()).stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.REVIEWER))
                .toList();

        if (reviewerLanes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Reviewer lane not found for ticketId=" + ticketId);
        }
        if (reviewerLanes.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Multiple reviewer lanes found for ticketId=" + ticketId);
        }

        final Lane reviewerLane = reviewerLanes.getFirst();
        if (!Objects.equals(reviewerLane.getStatus(), LaneStatus.IN_PROGRESS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reviewer lane cannot be completed in current state: laneId=" + reviewerLane.getId()
                            + ", laneStatus=" + reviewerLane.getStatus());
        }
        this.completeAgentTasks.complete(reviewerLane.getId(), List.of());
        return reviewerLane.getId();
    }
}
