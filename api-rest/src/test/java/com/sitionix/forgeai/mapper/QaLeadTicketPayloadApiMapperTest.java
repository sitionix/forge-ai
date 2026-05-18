package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AgentDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaLeadTicketPayloadApiMapperTest {

    private QaLeadTicketPayloadApiMapper qaLeadTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.qaLeadTicketPayloadApiMapper = new QaLeadTicketPayloadApiMapperImpl();
    }

    @Test
    void givenAnalyzerQaLeadHandoffDTO_whenAsQaLeadPayload_thenMapFields() {
        //given
        final AnalyzerQaLeadHandoffDTO source = this.getAnalyzerQaLeadHandoffDTO();

        //when
        final QaLeadPayload actual = this.qaLeadTicketPayloadApiMapper.asQaLeadPayload(source);

        //then
        assertThat(actual.getRequirements()).containsExactly("scope-r1");
        assertThat(actual.getConstraints()).containsExactly("qc1");
        assertThat(actual.getNonGoals()).containsExactly("qn1");
        assertThat(actual.getRisks()).containsExactly("risk-area-1");
        assertThat(actual.getDependencies()).containsExactly("qd1");
        assertThat(actual.getQualityFocus()).containsExactly("qf1");
        assertThat(actual.getEdgeConsiderations()).containsExactly("edge1");
    }

    private AnalyzerQaLeadHandoffDTO getAnalyzerQaLeadHandoffDTO() {
        return AnalyzerQaLeadHandoffDTO.builder()
                .writtenBy(AgentDTO.ANALYZER)
                .task("qa-task")
                .scope("automationservice-sox")
                .summary("qa-summary")
                .scopeRequirements(List.of("scope-r1"))
                .constraints(List.of("qc1"))
                .nonGoals(List.of("qn1"))
                .dependencies(List.of("qd1"))
                .qualityFocus(List.of("qf1"))
                .riskAreas(List.of("risk-area-1"))
                .edgeConsiderations(List.of("edge1"))
                .build();
    }
}
