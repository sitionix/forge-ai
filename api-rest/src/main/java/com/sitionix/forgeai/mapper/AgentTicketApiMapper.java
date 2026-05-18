package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

@Mapper(
        componentModel = "spring",
        uses = {
                ArchitectTicketPayloadApiMapper.class,
                QaLeadTicketPayloadApiMapper.class
        },
        imports = Agent.class
)
public interface AgentTicketApiMapper {

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.architectHandoff.scope")
    @Mapping(target = "agent", expression = "java(Agent.ARCHITECT)")
    @Mapping(target = "payload", source = "source.architectHandoff")
    AgentTicket<ArchitectPayload> asArchitectTicket(
            CompleteAnalyzerLaneRequestDTO source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.qaLeadHandoff.scope")
    @Mapping(target = "agent", expression = "java(Agent.QA_LEAD)")
    @Mapping(target = "payload", source = "source.qaLeadHandoff")
    AgentTicket<QaLeadPayload> asQaLeadTicket(
            CompleteAnalyzerLaneRequestDTO source,
            UUID ticketId);
}
