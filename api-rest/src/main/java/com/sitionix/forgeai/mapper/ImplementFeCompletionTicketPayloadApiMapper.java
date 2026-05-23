package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeAffectedSurfaceDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeChangedFileDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeAffectedSurface;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeCompletionPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ImplementFeCompletionTicketPayloadApiMapper {

    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", source = "changedFiles")
    @Mapping(target = "affectedSurfaces", source = "affectedSurfaces")
    @Mapping(target = "uiBehavior", source = "uiBehavior")
    ImplementFeCompletionPayload asImplementFeCompletionPayload(CompleteImplementFeLaneRequestDTO source);

    ImplementFeChangedFile asImplementFeChangedFile(ImplementFeChangedFileDTO source);

    @Mapping(target = "type", expression = "java(source.getType() == null ? null : source.getType().name())")
    ImplementFeAffectedSurface asImplementFeAffectedSurface(ImplementFeAffectedSurfaceDTO source);
}
