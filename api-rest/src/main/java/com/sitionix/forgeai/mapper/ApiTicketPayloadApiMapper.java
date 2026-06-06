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
public interface ApiTicketPayloadApiMapper {

    ApiPayload asApiPayload(ArchitectApiRequest source);

    ApiOperationPayload asApiOperationPayload(ArchitectApiOperation source);

    ApiParameterPayload asApiParameterPayload(ArchitectApiParameter source);

    ApiPayloadShape asApiPayloadShape(ArchitectApiPayloadShape source);

    ApiPayloadField asApiPayloadField(ArchitectApiField source);

    default String map(final Enum<?> source) {
        return source == null ? null : source.name();
    }
}
