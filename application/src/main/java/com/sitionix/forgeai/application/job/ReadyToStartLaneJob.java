package com.sitionix.forgeai.application.job;

import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Log
@Component
@RequiredArgsConstructor
public class ReadyToStartLaneJob {

    private final TicketRepository ticketRepository;
    private final ManageTicketOperatorRuns manageTicketOperatorRuns;
    private final TaskExecutor laneExecutionTaskExecutor;
    private final Set<UUID> dispatchingLaneIds = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${forge-ai.jobs.ready-to-start.fixed-delay-ms:5000}")
    public void run() {
        final List<ReadyToStartLane> readyLanes = this.ticketRepository.findAllReadyToStartLanes();
        log.info("Found %d ready lanes".formatted(readyLanes.size()));
        readyLanes.forEach(this::dispatchLaneSafely);
    }

    private void dispatchLaneSafely(final ReadyToStartLane lane) {
        if (this.manageTicketOperatorRuns.isExecutionBlocked(lane.getTicketId())) {
            log.info("Skipping ready lane because ticket operator run is cancelled: ticketId="
                    + lane.getTicketId() + ", laneId=" + lane.getLaneId() + ", agent=" + lane.getAgent().getId());
            return;
        }
        if (!this.dispatchingLaneIds.add(lane.getLaneId())) {
            log.info("Skipping ready lane because it is already being dispatched: laneId="
                    + lane.getLaneId() + ", agent=" + lane.getAgent().getId());
            return;
        }
        this.laneExecutionTaskExecutor.execute(() -> this.executeLaneSafely(lane));
    }

    private void executeLaneSafely(final ReadyToStartLane lane) {
        try {
            if (this.manageTicketOperatorRuns.isExecutionBlocked(lane.getTicketId())) {
                log.info("Skipping dispatched lane because ticket operator run is cancelled: ticketId="
                        + lane.getTicketId() + ", laneId=" + lane.getLaneId() + ", agent=" + lane.getAgent().getId());
                return;
            }
            lane.getAgent().executeLane(lane);
        } catch (final RuntimeException e) {
            log.severe("Failed to execute laneId=" + lane.getLaneId() + ", agent=" + lane.getAgent().getId() + ", error=" + e.getMessage());
        } finally {
            this.dispatchingLaneIds.remove(lane.getLaneId());
        }
    }
}
