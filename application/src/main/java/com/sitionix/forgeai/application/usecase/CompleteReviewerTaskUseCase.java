package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteReviewerTask;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteReviewerTaskUseCase implements CompleteReviewerTask {

    private final TicketRepository ticketRepository;

    public UUID complete(final UUID ticketId) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Reviewer ticket not found with ticketId=" + ticketId));

        final Lane reviewerLane = ticket.getLanes().stream()
                .filter(value -> value.getAgent() == Agent.REVIEWER)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Reviewer lane not found for ticketId=" + ticketId));
        reviewerLane.setStatus(LaneStatus.COMPLETED);
        ticket.setStatus(TicketStatus.RESOLVED);
        this.ticketRepository.save(ticket);
        return reviewerLane.getId();
    }
}
