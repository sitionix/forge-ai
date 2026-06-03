package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.infrastructure.mongodb.entity.operator.TicketOperatorRunDocument;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TicketOperatorRunEntityMapper {

    TicketOperatorRunDocument asDocument(TicketOperatorRun source);

    TicketOperatorRun asDomain(TicketOperatorRunDocument source);
}
