package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = LaneDependencyEntityMapper.class)
public interface LaneEntityMapper {

    @Mapping(target = "type", source = "agent")
    LaneDocument asLaneDocument(Lane lane);

    @Mapping(target = "agent", source = "type")
    Lane asLane(LaneDocument laneDocument);
}
