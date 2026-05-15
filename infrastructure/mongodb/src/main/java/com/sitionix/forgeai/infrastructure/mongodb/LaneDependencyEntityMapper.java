package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDependencyDocument;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface LaneDependencyEntityMapper {

    LaneDependencyDocument asLaneDependencyDocument(LaneDependency laneDependency);

    LaneDependency asLaneDependency(LaneDependencyDocument laneDependencyDocument);
}
