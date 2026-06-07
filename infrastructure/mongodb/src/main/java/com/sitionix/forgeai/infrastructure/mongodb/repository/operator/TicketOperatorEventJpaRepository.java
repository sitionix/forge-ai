package com.sitionix.forgeai.infrastructure.mongodb.repository.operator;

import com.sitionix.forgeai.infrastructure.mongodb.entity.operator.TicketOperatorEventDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TicketOperatorEventJpaRepository extends MongoRepository<TicketOperatorEventDocument, UUID> {

    List<TicketOperatorEventDocument> findByTicketIdOrderByTimestampDesc(UUID ticketId, Pageable pageable);

    void deleteByTicketId(UUID ticketId);
}
