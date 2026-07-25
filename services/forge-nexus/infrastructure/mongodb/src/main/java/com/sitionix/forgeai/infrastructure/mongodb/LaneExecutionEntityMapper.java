package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneStepExecutionDocument;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public abstract class LaneExecutionEntityMapper {

    public abstract LaneExecutionDocument asLaneExecutionDocument(LaneExecution source);

    public abstract LaneExecution asLaneExecution(LaneExecutionDocument source);

    public abstract LaneStepExecutionDocument asLaneStepExecutionDocument(LaneStepExecution source);

    public abstract LaneStepExecution asLaneStepExecution(LaneStepExecutionDocument source);
}
