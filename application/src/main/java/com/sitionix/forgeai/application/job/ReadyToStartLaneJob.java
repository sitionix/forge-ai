package com.sitionix.forgeai.application.job;

import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Log
@Component
@RequiredArgsConstructor
public class ReadyToStartLaneJob {

    private final TicketRepository ticketRepository;

    @Scheduled(fixedDelayString = "${forge-ai.jobs.ready-to-start.fixed-delay-ms:5000}")
    public void run() {
        final List<ReadyToStartLane> readyLanes = this.ticketRepository.findAllReadyToStartLanes();
        log.info("Found %d ready lanes".formatted(readyLanes.size()));
        readyLanes.forEach(this::executeLaneSafely);
    }

    private void executeLaneSafely(final ReadyToStartLane lane) {
        try {
            lane.getAgent().executeLane(lane);
        } catch (final RuntimeException e) {
            log.severe("Failed to execute laneId=" + lane.getLaneId() + ", agent=" + lane.getAgent().getId() + ", error=" + e.getMessage());
        }
    }
}
