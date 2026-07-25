package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public abstract class ImplementFeTicketPayloadApiMapper {

    public abstract ImplementFePayload asImplementFePayload(ArchitectImplementationHandoff source);
}
