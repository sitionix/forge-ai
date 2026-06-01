package com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution;

import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneStepExecutionDocument;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LaneStepExecutionJpaRepository extends MongoRepository<LaneStepExecutionDocument, UUID> {
}
