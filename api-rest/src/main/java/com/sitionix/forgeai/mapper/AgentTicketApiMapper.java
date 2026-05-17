package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

@Mapper(
        componentModel = "spring",
        uses = {
                ArchitectTicketPayloadApiMapper.class,
                QaLeadTicketPayloadApiMapper.class
        }
)
public interface AgentTicketApiMapper {

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "payload", source = "source.architectHandoff")
    AgentTicket<ArchitectPayload> asArchitectTicket(
            CompleteAnalyzerLaneRequestDTO source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "payload", source = "source.qaLeadHandoff")
    AgentTicket<QaLeadPayload> asQaLeadTicket(
            CompleteAnalyzerLaneRequestDTO source,
            UUID ticketId);
}
