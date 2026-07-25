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
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadUnitTestNote;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaLeadCompletionTicketPayloadApiMapperTest {

    private QaLeadCompletionTicketPayloadApiMapper qaLeadCompletionTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.qaLeadCompletionTicketPayloadApiMapper = new QaLeadCompletionTicketPayloadApiMapperImpl();
    }

    @Test
    void givenCompleteQaLeadLaneRequest_whenAsTestItPayload_thenMapFields() {
        //given
        final CompleteQaLeadLaneRequestDTO source = this.getSource();

        //when
        final QaLeadTestItPayload actual = this.qaLeadCompletionTicketPayloadApiMapper.asTestItPayload(source);

        //then
        assertThat(actual).isEqualTo(this.getExpectedTestItPayload());
    }

    @Test
    void givenCompleteQaLeadLaneRequest_whenAsTestUnitPayload_thenMapFields() {
        //given
        final CompleteQaLeadLaneRequestDTO source = this.getSource();

        //when
        final QaLeadTestUnitPayload actual = this.qaLeadCompletionTicketPayloadApiMapper.asTestUnitPayload(source);

        //then
        assertThat(actual).isEqualTo(this.getExpectedTestUnitPayload());
    }

    private CompleteQaLeadLaneRequestDTO getSource() {
        return CompleteQaLeadLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("summary")
                .integrationTestCases(List.of(
                        QaLeadIntegrationTestCaseDTO.builder()
                                .title("Create agent action successfully")
                                .flow(QaLeadIntegrationFlowDTO.builder()
                                        .name("Create agent action")
                                        .method(QaLeadIntegrationFlowDTO.MethodEnum.POST)
                                        .path("/api/v1/agent-actions")
                                        .build())
                                .given(List.of("ticket exists"))
                                .when(List.of("POST request submitted"))
                                .then(List.of("response 200"))
                                .dataChecks(List.of(QaLeadDataCheckDTO.builder()
                                        .target("agent ticket persisted")
                                        .expectation("created record")
                                        .build()))
                                .priority(QaLeadIntegrationTestCaseDTO.PriorityEnum.HIGH)
                                .build()
                ))
                .unitTestNotes(List.of(
                        QaLeadUnitTestNoteDTO.builder()
                                .target("CreateAgentActionUseCase")
                                .note("Validate missing title handling")
                                .build()
                ))
                .build();
    }

    private QaLeadTestItPayload getExpectedTestItPayload() {
        final QaLeadTestItPayload payload = new QaLeadTestItPayload();
        payload.setTask("Prepare integration test execution context");
        payload.setScope("automationservice-sox");
        payload.setSummary("summary");
        payload.setIntegrationTestCases(Set.of(this.getExpectedIntegrationTestCase()));
        payload.setUnitTestNotes(Set.of(this.getExpectedUnitTestNote()));
        return payload;
    }

    private QaLeadTestUnitPayload getExpectedTestUnitPayload() {
        final QaLeadTestUnitPayload payload = new QaLeadTestUnitPayload();
        payload.setTask("Prepare unit test execution context");
        payload.setScope("automationservice-sox");
        payload.setSummary("summary");
        payload.setUnitTestNotes(Set.of(this.getExpectedUnitTestNote()));
        return payload;
    }

    private QaLeadIntegrationTestCase getExpectedIntegrationTestCase() {
        return new QaLeadIntegrationTestCase(
                "Create agent action successfully",
                new QaLeadIntegrationFlow("Create agent action", "POST", "/api/v1/agent-actions", null),
                Set.of("ticket exists"),
                Set.of("POST request submitted"),
                Set.of("response 200"),
                Set.of(new QaLeadDataCheck("agent ticket persisted", "created record")),
                "HIGH"
        );
    }

    private QaLeadUnitTestNote getExpectedUnitTestNote() {
        return new QaLeadUnitTestNote("CreateAgentActionUseCase", "Validate missing title handling");
    }
}
