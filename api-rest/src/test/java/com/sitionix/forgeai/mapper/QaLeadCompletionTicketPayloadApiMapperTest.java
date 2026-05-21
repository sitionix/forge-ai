package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadDataCheckDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadUnitTestNoteDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
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
        final TestItPayload actual = this.qaLeadCompletionTicketPayloadApiMapper.asTestItPayload(source);

        //then
        assertThat(actual).isEqualTo(this.getExpectedTestItPayload());
    }

    @Test
    void givenCompleteQaLeadLaneRequest_whenAsTestUiPayload_thenMapFields() {
        //given
        final CompleteQaLeadLaneRequestDTO source = this.getSource();

        //when
        final TestUiPayload actual = this.qaLeadCompletionTicketPayloadApiMapper.asTestUiPayload(source);

        //then
        assertThat(actual).isEqualTo(this.getExpectedTestUiPayload());
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

    private TestItPayload getExpectedTestItPayload() {
        final TestItPayload payload = new TestItPayload();
        payload.setTask("Prepare integration test execution context for automationservice-sox");
        payload.setScope("automationservice-sox");
        payload.setSummary("summary");
        payload.setIntegrationTestCases(Set.of("title=Create agent action successfully | flow=POST /api/v1/agent-actions | given=ticket exists | when=POST request submitted | then=response 200 | dataChecks=agent ticket persisted -> created record | priority=HIGH"));
        payload.setUnitTestNotes(Set.of("CreateAgentActionUseCase :: Validate missing title handling"));
        return payload;
    }

    private TestUiPayload getExpectedTestUiPayload() {
        final TestUiPayload payload = new TestUiPayload();
        payload.setTask("Prepare UI test execution context for automationservice-sox");
        payload.setScope("automationservice-sox");
        payload.setSummary("summary");
        payload.setIntegrationTestCases(Set.of("title=Create agent action successfully | flow=POST /api/v1/agent-actions | given=ticket exists | when=POST request submitted | then=response 200 | dataChecks=agent ticket persisted -> created record | priority=HIGH"));
        payload.setUnitTestNotes(Set.of("CreateAgentActionUseCase :: Validate missing title handling"));
        return payload;
    }
}
