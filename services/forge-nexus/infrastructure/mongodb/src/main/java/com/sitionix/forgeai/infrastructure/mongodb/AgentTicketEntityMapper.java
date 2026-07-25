package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public abstract class AgentTicketEntityMapper {

    public abstract AgentTicketDocument asAgentTicketDocument(AgentTicket<? extends AgentTicketPayload> source);

    public abstract AgentTicket<AgentTicketPayload> asAgentTicket(AgentTicketDocument source);
}
