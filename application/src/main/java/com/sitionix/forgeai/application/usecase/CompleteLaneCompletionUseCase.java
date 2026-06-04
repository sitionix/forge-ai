package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionCommands;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionConflictException;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteLaneCompletion;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompleteLaneCompletionUseCase implements CompleteLaneCompletion {

    private final TicketRepository ticketRepository;

    @Override
    public void completeLane(final LaneCompletionCommands.CompleteLane command) {
        final Ticket ticket = this.ticketRepository.findById(command.ticketId())
                .orElseThrow(() -> new LaneCompletionConflictException(
                        "Ticket not found with ticketId=" + command.ticketId()));
        final Lane lane = this.requireLane(ticket, command);
        this.validateInProgress(lane);

        final ReadyToStartLane readyToStartLane = this.asReadyToStartLane(ticket, lane);
        final Map<String, Object> completionPayload = command.completionPayload() == null
                ? Map.of()
                : command.completionPayload();

        lane.getAgent().validateFinalCompletionPayload(readyToStartLane, completionPayload);
        lane.getAgent().completeLane(readyToStartLane, completionPayload);
    }

    private Lane requireLane(final Ticket ticket, final LaneCompletionCommands.CompleteLane command) {
        if (ticket.getLanes() == null) {
            throw new LaneCompletionConflictException(
                    "Lane not found for ticketId=" + command.ticketId() + ", laneId=" + command.laneId());
        }
        return ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), command.laneId()))
                .findFirst()
                .orElseThrow(() -> new LaneCompletionConflictException(
                        "Lane not found for ticketId=" + command.ticketId() + ", laneId=" + command.laneId()));
    }

    private void validateInProgress(final Lane lane) {
        if (Objects.equals(lane.getStatus(), LaneStatus.IN_PROGRESS)) {
            return;
        }
        throw new LaneCompletionConflictException(
                "Lane cannot be completed in current state: laneId=" + lane.getId()
                        + ", laneStatus=" + lane.getStatus());
    }

    private ReadyToStartLane asReadyToStartLane(final Ticket ticket, final Lane lane) {
        return ReadyToStartLane.builder()
                .ticketId(ticket.getId())
                .ticketKey(ticket.getTicketKey())
                .sourceTerminalTty(ticket.getSourceTerminalTty())
                .laneId(lane.getId())
                .agent(lane.getAgent())
                .scope(lane.getScope())
                .serviceId(lane.getServiceId())
                .attempt(lane.getAttempt())
                .build();
    }
}
