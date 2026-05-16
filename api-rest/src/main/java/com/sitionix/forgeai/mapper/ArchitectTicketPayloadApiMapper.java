package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", imports = Agent.class)
public interface ArchitectTicketPayloadApiMapper {

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "agent", expression = "java(Agent.ARCHITECT)")
    ArchitectPayload asArchitectPayload(AnalyzerArchitectHandoffDTO source);
}
