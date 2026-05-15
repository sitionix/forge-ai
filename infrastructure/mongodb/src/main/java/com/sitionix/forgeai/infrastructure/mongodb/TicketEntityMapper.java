package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = LaneEntityMapper.class)
public interface TicketEntityMapper {
    TicketDocument asTicketDocument(Ticket ticket);

    Ticket asTicket(TicketDocument ticketDocument);
}
