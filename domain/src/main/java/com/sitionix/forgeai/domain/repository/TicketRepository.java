package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists Forge AI tickets.
 */
public interface TicketRepository {

    Ticket save(Ticket ticket);

    List<ReadyToStartLane> findAllReadyToStartLanes();

    String findTicketContentById(UUID ticketId);

    Optional<Lane> findByLaneId(UUID laneId);

    void updateLaneStatus(UUID laneId, LaneStatus laneStatus);
}
