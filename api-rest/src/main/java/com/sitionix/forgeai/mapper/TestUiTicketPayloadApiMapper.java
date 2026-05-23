package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeChangedFileDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import java.util.List;
import java.util.Set;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TestUiTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write UI tests for frontend changed files in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", source = "changedFiles")
    @Mapping(target = "unitTestNotes", ignore = true)
    TestUiPayload asTestUiPayload(CompleteImplementFeLaneRequestDTO source);

    @IterableMapping(elementTargetType = ImplementFeChangedFile.class)
    Set<ImplementFeChangedFile> asChangedFiles(List<ImplementFeChangedFileDTO> source);

    ImplementFeChangedFile asChangedFile(ImplementFeChangedFileDTO source);
}
