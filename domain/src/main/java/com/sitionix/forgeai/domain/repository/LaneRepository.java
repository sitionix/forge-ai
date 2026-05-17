package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import java.util.List;
import java.util.UUID;

public interface LaneRepository {

    Lane findLaneToProduce(UUID relatedLaneId, String scope, Agent agent);

    void assignInputTaskId(UUID laneId, UUID inputTaskId);

    List<Lane> findProducedLanes(UUID sourceLaneId);
}
