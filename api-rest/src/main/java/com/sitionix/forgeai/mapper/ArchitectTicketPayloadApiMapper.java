package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ArchitectTicketPayloadApiMapper {

    ArchitectPayload asArchitectPayload(AnalyzerArchitectHandoffDTO source);
}
