package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompleteAgentUseCase implements CompleteAgentLane {

    private final TicketRepository ticketRepository;
    private final LaneRepository laneRepository;


    @Override
    public void completeAndPrepareAgents(final UUID laneId) {
        final Lane lane = this.ticketRepository.findByLaneId(laneId)
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + laneId));

        final List<Lane> producedLanes = this.laneRepository.findProducedLanes(lane.getId());

        if (this.readyToComplete(producedLanes)) {
            this.ticketRepository.updateLaneStatus(laneId, LaneStatus.COMPLETED);
            this.moveReadyProducedLanes(producedLanes);
        }
    }

    private boolean readyToComplete(final List<Lane> lanes) {
        return lanes.stream().allMatch(value ->
                (value.getInputTaskIds() != null && !value.getInputTaskIds().isEmpty())
                        || LaneStatus.NOT_NEEDED.equals(value.getStatus()));
    }

    private void moveReadyProducedLanes(final List<Lane> producedLanes) {
        producedLanes.stream()
                .filter(value -> !LaneStatus.NOT_NEEDED.equals(value.getStatus()))
                .filter(value -> this.ticketRepository.isReadyToStart(value.getId()))
                .forEach(value -> this.ticketRepository.updateLaneStatus(value.getId(), LaneStatus.READY_TO_START));
    }

}
