package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneStepExecutionDocument;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface LaneExecutionEntityMapper {

    LaneExecutionDocument asLaneExecutionDocument(LaneExecution source);

    LaneStepExecutionDocument asLaneStepExecutionDocument(LaneStepExecution source);
}
