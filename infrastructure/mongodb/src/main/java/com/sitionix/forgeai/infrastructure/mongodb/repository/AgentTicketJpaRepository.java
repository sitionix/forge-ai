package com.sitionix.forgeai.infrastructure.mongodb.repository;

import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;

public interface AgentTicketJpaRepository extends MongoRepository<AgentTicketDocument, UUID> {

    java.util.Optional<AgentTicketDocument> findFirstByLaneId(UUID laneId);
}
