package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.UnitTestSonarDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class UnitTestCompletionTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Prepare reviewer execution context\")")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "affectedFiles", source = "affectedFiles")
    @Mapping(target = "sonar", source = "sonar")
    public abstract ReviewerPayload asReviewerPayload(CompleteUnitTestLaneRequestDTO source);

    public abstract UnitTestSonar asUnitTestSonar(UnitTestSonarDTO source);
}
