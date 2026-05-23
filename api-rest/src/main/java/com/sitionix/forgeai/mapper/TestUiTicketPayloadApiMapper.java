package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeAffectedSurfaceDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeChangedFileDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeAffectedSurface;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TestUiTicketPayloadApiMapper {

    TestUiPayload asTestUiPayload(CompleteImplementFeLaneRequestDTO source);

    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", expression = "java(new LinkedHashSet<>())")
    @Mapping(target = "affectedSurfaces", expression = "java(new LinkedHashSet<>())")
    @Mapping(target = "uiBehavior", expression = "java(new LinkedHashSet<>())")
    TestUiPayload asTestUiPayload(CompleteQaLeadLaneRequestDTO source);

    @IterableMapping(elementTargetType = ImplementFeChangedFile.class)
    Set<ImplementFeChangedFile> asChangedFiles(List<ImplementFeChangedFileDTO> source);

    ImplementFeChangedFile asChangedFile(ImplementFeChangedFileDTO source);

    @IterableMapping(elementTargetType = ImplementFeAffectedSurface.class)
    Set<ImplementFeAffectedSurface> asAffectedSurfaces(List<ImplementFeAffectedSurfaceDTO> source);

    ImplementFeAffectedSurface asAffectedSurface(ImplementFeAffectedSurfaceDTO source);
}
