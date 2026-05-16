package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.List;
import java.util.UUID;

/**
 * Persists Forge AI tickets.
 */
public interface TicketRepository {

    Ticket save(Ticket ticket);

    List<ReadyToStartLane> findAllReadyToStartLanes();

    String findTicketContentById(UUID ticketId);

    void updateLaneStatus(UUID laneId, LaneStatus laneStatus);
}
