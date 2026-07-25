package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeChangedFileDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementationSonarDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import java.util.List;
import java.util.Set;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class TestUnitTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write unit tests for backend changed files in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", source = "changedFiles")
    @Mapping(target = "sonar", source = "sonar")
    @Mapping(target = "unitTestNotes", ignore = true)
    public abstract TestUnitPayload asTestUnitPayload(CompleteImplementBeLaneRequestDTO source);

    @IterableMapping(elementTargetType = ImplementBeChangedFile.class)
    public abstract Set<ImplementBeChangedFile> asChangedFiles(List<ImplementBeChangedFileDTO> source);

    public abstract ImplementBeChangedFile asChangedFile(ImplementBeChangedFileDTO source);

    public abstract UnitTestSonar asUnitTestSonar(ImplementationSonarDTO source);
}
