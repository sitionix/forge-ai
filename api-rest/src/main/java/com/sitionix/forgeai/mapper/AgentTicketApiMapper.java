package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.*;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

@Mapper(
        componentModel = "spring",
        uses = {
                ArchitectTicketPayloadApiMapper.class,
                QaLeadTicketPayloadApiMapper.class,
                QaLeadCompletionTicketPayloadApiMapper.class,
                ImplementBeTicketPayloadApiMapper.class,
                TestUnitTicketPayloadApiMapper.class,
                TestItTicketPayloadApiMapper.class,
                UnitTestCompletionTicketPayloadApiMapper.class,
                ImplementFeTicketPayloadApiMapper.class,
                ApiTicketPayloadApiMapper.class,
                EventTicketPayloadApiMapper.class
        },
        imports = {
                Agent.class,
                ScopeMode.class
        }
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

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.scope")
    @Mapping(target = "agent", expression = "java(Agent.TEST_UNIT)")
    @Mapping(target = "payload", source = "source")
    AgentTicket<TestUnitPayload> asTestUnitTicket(
            CompleteQaLeadLaneRequestDTO source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", expression = "java(ScopeMode.GLOBAL_SCOPE)")
    @Mapping(target = "agent", expression = "java(Agent.REVIEWER)")
    @Mapping(target = "payload", source = "source")
    AgentTicket<ReviewerPayload> asReviewerTicket(
            CompleteUnitTestLaneRequestDTO source,
            UUID ticketId);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "scope", source = "source.scope")
    @Mapping(target = "agent", expression = "java(Agent.TEST_IT)")
    @Mapping(target = "payload", source = "source")
    AgentTicket<TestItPayload> asTestItTicket(
            CompleteQaLeadLaneRequestDTO source,
            UUID ticketId);

    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "coveredCases", source = "coveredCases")
    TestItCompletionPayload asTestItCompletionPayload(CompleteItTestLaneRequestDTO source);

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "laneId", source = "laneId")
    @Mapping(target = "status", constant = "CONSUMED")
    @Mapping(target = "scope", source = "source.scope")
    @Mapping(target = "agent", expression = "java(Agent.TEST_IT)")
    @Mapping(target = "payload", source = "source")
    @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
    @Mapping(target = "updatedAt", expression = "java(java.time.LocalDateTime.now())")
    AgentTicket<TestItCompletionPayload> asTestItCompletionTicket(
            CompleteItTestLaneRequestDTO source,
            UUID ticketId,
            UUID laneId);

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

}
