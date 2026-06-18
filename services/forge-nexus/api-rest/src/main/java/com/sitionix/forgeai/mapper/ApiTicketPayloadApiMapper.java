package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectApiField;
import com.app_afesox.fgaisox.api_first.dto.ArchitectApiOperation;
import com.app_afesox.fgaisox.api_first.dto.ArchitectApiParameter;
import com.app_afesox.fgaisox.api_first.dto.ArchitectApiPayloadShape;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiOperationPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiParameterPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayloadField;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayloadShape;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public abstract class ApiTicketPayloadApiMapper {

    public abstract ApiPayload asApiPayload(ArchitectApiRequest source);

    public abstract ApiOperationPayload asApiOperationPayload(ArchitectApiOperation source);

    public abstract ApiParameterPayload asApiParameterPayload(ArchitectApiParameter source);

    public abstract ApiPayloadShape asApiPayloadShape(ArchitectApiPayloadShape source);

    public abstract ApiPayloadField asApiPayloadField(ArchitectApiField source);

    public String map(final Enum<?> source) {
        return source == null ? null : source.name();
    }
}
