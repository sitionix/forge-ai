package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.infrastructure.mongodb.entity.operator.TicketOperatorRunDocument;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public abstract class TicketOperatorRunEntityMapper {

    public abstract TicketOperatorRunDocument asDocument(TicketOperatorRun source);

    public abstract TicketOperatorRun asDomain(TicketOperatorRunDocument source);
}
