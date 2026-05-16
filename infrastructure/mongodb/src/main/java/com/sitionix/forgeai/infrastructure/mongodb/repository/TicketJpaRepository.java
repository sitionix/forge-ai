package com.sitionix.forgeai.infrastructure.mongodb.repository;

import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.projection.ReadyToStartLaneProjection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TicketJpaRepository extends MongoRepository<TicketDocument, UUID> {

    @Aggregation(pipeline = {
            "{ $match: { status: 'OPEN' } }",
            "{ $unwind: '$lanes' }",
            "{ $match: { 'lanes.status': 'READY_TO_START' } }",
            "{ $project: { " +
                    "ticketId: '$_id', " +
                    "ticketKey: '$ticketKey', " +
                    "sourceTerminalTty: '$sourceTerminalTty', " +
                    "laneId: '$lanes._id', " +
                    "agent: '$lanes.type', " +
                    "scope: '$lanes.scope', " +
                    "serviceId: '$lanes.serviceId', " +
                    "attempt: '$lanes.attempt' " +
                    "} }"
    })
    List<ReadyToStartLaneProjection> findAllReadyToStartLanes();
}
