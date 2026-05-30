package com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution;

import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LaneExecutionJpaRepository extends MongoRepository<LaneExecutionDocument, UUID> {
}
