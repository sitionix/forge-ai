package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeChangedFileDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import java.util.List;
import java.util.Set;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TestUnitTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write unit tests for backend changed files in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", source = "changedFiles")
    @Mapping(target = "unitTestNotes", ignore = true)
    TestUnitPayload asTestUnitPayload(CompleteImplementBeLaneRequestDTO source);

    @IterableMapping(elementTargetType = ImplementBeChangedFile.class)
    Set<ImplementBeChangedFile> asChangedFiles(List<ImplementBeChangedFileDTO> source);

    ImplementBeChangedFile asChangedFile(ImplementBeChangedFileDTO source);
}
