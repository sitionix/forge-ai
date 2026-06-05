package com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution;

import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LaneExecutionJpaRepository extends MongoRepository<LaneExecutionDocument, UUID> {

    List<LaneExecutionDocument> findByStatusNotIn(Collection<LaneExecutionStatus> statuses);

    List<LaneExecutionDocument> findByTicketId(UUID ticketId);

    List<LaneExecutionDocument> findByTicketIdAndStatusNotIn(UUID ticketId, Collection<LaneExecutionStatus> statuses);

    void deleteByTicketId(UUID ticketId);
}
