package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadUnitTestNoteDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import java.util.List;
import java.util.Set;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.IterableMapping;

@Mapper(
        componentModel = "spring",
        uses = QaLeadCompletionTicketPayloadApiMapperSupport.class,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR
)
public interface QaLeadCompletionTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Prepare unit test execution context\")")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "changedFiles", ignore = true)
    @Mapping(target = "unitTestNotes", source = "unitTestNotes")
    TestUnitPayload asTestUnitPayload(CompleteQaLeadLaneRequestDTO source);

    @Mapping(target = "task", expression = "java(\"Prepare integration test execution context\")")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "integrationTestCases", source = "integrationTestCases")
    @Mapping(target = "unitTestNotes", source = "unitTestNotes")
    TestItPayload asTestItPayload(CompleteQaLeadLaneRequestDTO source);

    @IterableMapping(qualifiedByName = "asIntegrationTestCase")
    Set<String> asIntegrationTestCases(List<QaLeadIntegrationTestCaseDTO> source);

    @IterableMapping(qualifiedByName = "asUnitTestNote")
    Set<String> asUnitTestNotes(List<QaLeadUnitTestNoteDTO> source);
}
