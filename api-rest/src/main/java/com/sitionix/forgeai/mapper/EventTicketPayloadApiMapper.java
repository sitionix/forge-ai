package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface EventTicketPayloadApiMapper {

    EventPayload asEventPayload(ArchitectEventRequest source);
}
