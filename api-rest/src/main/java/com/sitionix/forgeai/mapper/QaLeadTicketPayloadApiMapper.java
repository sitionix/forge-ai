package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", imports = Agent.class)
public interface QaLeadTicketPayloadApiMapper {

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "agent", expression = "java(Agent.QA_LEAD)")
    @Mapping(target = "requirements", source = "scopeRequirements")
    @Mapping(target = "nonGoals", source = "nonGoals")
    @Mapping(target = "risks", source = "riskAreas")
    QaLeadPayload asQaLeadPayload(AnalyzerQaLeadHandoffDTO source);
}
