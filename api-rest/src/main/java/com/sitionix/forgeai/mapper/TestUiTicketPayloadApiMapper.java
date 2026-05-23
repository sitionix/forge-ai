package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TestUiTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write UI tests for implemented frontend changes in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "integrationTestCases", ignore = true)
    @Mapping(target = "unitTestNotes", ignore = true)
    TestUiPayload asTestUiPayload(CompleteImplementFeLaneRequestDTO source);
}
