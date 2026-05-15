package com.sitionix.forgeai.infrastructure.mongodb.repository;

import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TicketJpaRepository extends MongoRepository<TicketDocument, UUID> {
}
