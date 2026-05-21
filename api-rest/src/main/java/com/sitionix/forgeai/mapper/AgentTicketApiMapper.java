package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.*;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

@Mapper(
        componentModel = "spring",
        uses = {
                ArchitectTicketPayloadApiMapper.class,
                QaLeadTicketPayloadApiMapper.class,
                ImplementBeTicketPayloadApiMapper.class,
                ImplementFeTicketPayloadApiMapper.class,
                ApiTicketPayloadApiMapper.class,
                EventTicketPayloadApiMapper.class,
                TestUnitTicketPayloadApiMapper.class,
                TestItTicketPayloadApiMapper.class
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

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.implementationHandoff.scope")
    @Mapping(target = "agent", expression = "java(Agent.IMPLEMENT_BE)")
    @Mapping(target = "payload", source = "source.implementationHandoff")
    AgentTicket<ImplementBePayload> asImplementBeTicket(
            CompleteArchitectLaneRequest source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.implementationHandoff.scope")
    @Mapping(target = "agent", expression = "java(Agent.IMPLEMENT_FE)")
    @Mapping(target = "payload", source = "source.implementationHandoff")
    AgentTicket<ImplementFePayload> asImplementFeTicket(
            CompleteArchitectLaneRequest source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.apiRequest.scope")
    @Mapping(target = "agent", expression = "java(Agent.API)")
    @Mapping(target = "payload", source = "source.apiRequest")
    AgentTicket<ApiPayload> asApiTicket(
            CompleteArchitectLaneRequest source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.eventRequest.scope")
    @Mapping(target = "agent", expression = "java(Agent.EVENT)")
    @Mapping(target = "payload", source = "source.eventRequest")
    AgentTicket<EventPayload> asEventTicket(
            CompleteArchitectLaneRequest source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.scope")
    @Mapping(target = "agent", expression = "java(Agent.TEST_UNIT)")
    @Mapping(target = "payload", source = "source")
    AgentTicket<TestUnitPayload> asTestUnitTicket(
            CompleteImplementBeLaneRequestDTO source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.scope")
    @Mapping(target = "agent", expression = "java(Agent.TEST_IT)")
    @Mapping(target = "payload", source = "source")
    AgentTicket<TestItPayload> asTestItTicket(
            CompleteImplementBeLaneRequestDTO source,
            UUID ticketId);

}
