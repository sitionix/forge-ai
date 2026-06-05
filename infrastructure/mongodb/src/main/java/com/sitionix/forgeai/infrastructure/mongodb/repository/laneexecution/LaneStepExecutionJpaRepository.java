package com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution;

import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneStepExecutionDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LaneStepExecutionJpaRepository extends MongoRepository<LaneStepExecutionDocument, UUID> {

    List<LaneStepExecutionDocument> findByExecutionIdOrderByStepOrderAsc(UUID executionId);
}
