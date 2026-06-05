package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventPayloadField;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayloadField;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface EventTicketPayloadApiMapper {

    EventPayload asEventPayload(ArchitectEventRequest source);

    EventPayloadField asEventPayloadField(ArchitectEventPayloadField source);
}
