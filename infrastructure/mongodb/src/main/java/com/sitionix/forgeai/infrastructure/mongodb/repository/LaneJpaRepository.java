package com.sitionix.forgeai.infrastructure.mongodb.repository;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface LaneJpaRepository extends MongoRepository<TicketDocument, UUID> {

    @Aggregation(pipeline = {
            "{ $match: { 'lanes._id': ?0 } }",
            "{ $unwind: '$lanes' }",
            "{ $match: { 'lanes.scope': ?1, 'lanes.type': ?2 } }",
            "{ $replaceRoot: { newRoot: '$lanes' } }"
    })
    List<LaneDocument> findLanesToProduce(UUID relatedLaneId, String scope, Agent agent);

    @Aggregation(pipeline = {
            "{ $match: { 'lanes._id': ?0 } }",
            "{ $limit: 1 }"
    })
    Optional<TicketDocument> findTicketByLaneId(UUID laneId);

    @Query("{ 'lanes._id': ?0 }")
    @Update("{ '$set': { 'lanes.$.inputTaskId': ?1 } }")
    long assignInputTaskId(UUID laneId, UUID inputTaskId);
}
