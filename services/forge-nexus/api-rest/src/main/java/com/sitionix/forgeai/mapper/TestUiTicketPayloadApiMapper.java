package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeAffectedSurfaceDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeChangedFileDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementationSonarDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeAffectedSurface;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import java.util.List;
import java.util.Set;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class TestUiTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write UI tests for frontend changed files in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", source = "changedFiles")
    @Mapping(target = "affectedSurfaces", source = "affectedSurfaces")
    @Mapping(target = "uiBehavior", source = "uiBehavior")
    @Mapping(target = "sonar", source = "sonar")
    @Mapping(target = "unitTestNotes", ignore = true)
    public abstract TestUiPayload asTestUiPayload(CompleteImplementFeLaneRequestDTO source);

    @IterableMapping(elementTargetType = ImplementFeChangedFile.class)
    public abstract Set<ImplementFeChangedFile> asChangedFiles(List<ImplementFeChangedFileDTO> source);

    public abstract ImplementFeChangedFile asChangedFile(ImplementFeChangedFileDTO source);

    @IterableMapping(elementTargetType = ImplementFeAffectedSurface.class)
    public abstract Set<ImplementFeAffectedSurface> asAffectedSurfaces(List<ImplementFeAffectedSurfaceDTO> source);

    @Mapping(target = "type", source = "type")
    public abstract ImplementFeAffectedSurface asAffectedSurface(ImplementFeAffectedSurfaceDTO source);

    public abstract UnitTestSonar asUnitTestSonar(ImplementationSonarDTO source);
}
