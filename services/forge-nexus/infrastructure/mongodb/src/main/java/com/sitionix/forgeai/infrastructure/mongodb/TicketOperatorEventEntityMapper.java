package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.infrastructure.mongodb.entity.operator.TicketOperatorEventDocument;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class TicketOperatorEventEntityMapper {

    @Mapping(target = "id", expression = "java(newId())")
    public abstract TicketOperatorEventDocument asDocument(TicketOperatorEvent source);

    public abstract TicketOperatorEvent asDomain(TicketOperatorEventDocument source);

    protected UUID newId() {
        return UUID.randomUUID();
    }
}
