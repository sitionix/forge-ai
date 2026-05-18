package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ApiTicketPayloadApiMapper {

    ApiPayload asApiPayload(ArchitectApiRequest source);
}
