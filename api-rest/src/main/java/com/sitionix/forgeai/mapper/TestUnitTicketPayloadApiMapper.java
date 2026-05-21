package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeChangedFileDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import java.util.LinkedHashSet;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TestUnitTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write unit tests for backend changed files in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", expression = "java(this.asChangedFiles(source.getChangedFiles()))")
    TestUnitPayload asTestUnitPayload(CompleteImplementBeLaneRequestDTO source);

    default Set<String> asChangedFiles(final java.util.List<ImplementBeChangedFileDTO> source) {
        return source.stream()
                .map(value -> value.getPath() + " :: " + value.getReason())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
