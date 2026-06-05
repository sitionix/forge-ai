package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadDataCheckDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadUnitTestNoteDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadDataCheck;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadIntegrationFlow;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadIntegrationTestCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadUnitTestNote;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class QaLeadCompletionTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Prepare unit test execution context\")")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "unitTestNotes", source = "unitTestNotes")
    public abstract QaLeadTestUnitPayload asTestUnitPayload(CompleteQaLeadLaneRequestDTO source);

    @Mapping(target = "task", expression = "java(\"Prepare integration test execution context\")")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "integrationTestCases", source = "integrationTestCases")
    @Mapping(target = "unitTestNotes", source = "unitTestNotes")
    public abstract QaLeadTestItPayload asTestItPayload(CompleteQaLeadLaneRequestDTO source);

    @Mapping(target = "task", expression = "java(\"Prepare UI test execution context\")")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "unitTestNotes", source = "unitTestNotes")
    public abstract QaLeadTestUiPayload asTestUiPayload(CompleteQaLeadLaneRequestDTO source);

    public abstract Set<QaLeadIntegrationTestCase> asIntegrationTestCases(List<QaLeadIntegrationTestCaseDTO> source);

    public abstract Set<QaLeadUnitTestNote> asUnitTestNotes(List<QaLeadUnitTestNoteDTO> source);

    public abstract QaLeadIntegrationTestCase asIntegrationTestCase(QaLeadIntegrationTestCaseDTO source);

    public abstract QaLeadIntegrationFlow asIntegrationFlow(QaLeadIntegrationFlowDTO source);

    public abstract Set<QaLeadDataCheck> asDataChecks(List<QaLeadDataCheckDTO> source);

    public abstract QaLeadDataCheck asDataCheck(QaLeadDataCheckDTO source);

    public abstract QaLeadUnitTestNote asUnitTestNote(QaLeadUnitTestNoteDTO source);
}
