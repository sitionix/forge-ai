package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface QaLeadTicketPayloadApiMapper {

    @Mapping(target = "requirements", source = "scopeRequirements")
    @Mapping(target = "nonGoals", source = "nonGoals")
    @Mapping(target = "risks", source = "riskAreas")
    QaLeadPayload asQaLeadPayload(AnalyzerQaLeadHandoffDTO source);
}
