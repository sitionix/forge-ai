package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import org.springframework.stereotype.Component;

@Component
public class AgentTicketEntityMapper {

    public <P extends AgentTicketPayload> AgentTicketDocument asAgentTicketDocument(final AgentTicket<P> source) {
        return new AgentTicketDocument(
                source.getId(),
                source.getTicketId(),
                source.getLaneId(),
                source.getStatus(),
                source.getPayload(),
                source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    public <P extends AgentTicketPayload> AgentTicket<P> asAgentTicket(final AgentTicketDocument source) {
        return AgentTicket.<P>builder()
                .id(source.getId())
                .ticketId(source.getTicketId())
                .laneId(source.getLaneId())
                .status(source.getStatus())
                .payload((P) source.getPayload())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }
}
