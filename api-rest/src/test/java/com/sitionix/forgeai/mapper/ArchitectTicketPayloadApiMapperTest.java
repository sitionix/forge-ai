package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AgentDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectTicketPayloadApiMapperTest {

    private ArchitectTicketPayloadApiMapper architectTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.architectTicketPayloadApiMapper = new ArchitectTicketPayloadApiMapperImpl();
    }

    @Test
    void givenAnalyzerArchitectHandoffDTO_whenAsArchitectPayload_thenMapFields() {
        //given
        final AnalyzerArchitectHandoffDTO source = this.getAnalyzerArchitectHandoffDTO();

        //when
        final ArchitectPayload actual = this.architectTicketPayloadApiMapper.asArchitectPayload(source);

        //then
        assertThat(actual.getRequirements()).containsExactlyInAnyOrder("r1", "r2");
        assertThat(actual.getConstraints()).containsExactly("c1");
        assertThat(actual.getNonGoals()).containsExactly("n1");
        assertThat(actual.getRisks()).containsExactly("risk1");
        assertThat(actual.getDependencies()).containsExactly("dep1");
    }

    private AnalyzerArchitectHandoffDTO getAnalyzerArchitectHandoffDTO() {
        return AnalyzerArchitectHandoffDTO.builder()
                .writtenBy(AgentDTO.ANALYZER)
                .task("architect-task")
                .scope("automationservice-sox")
                .summary("architect-summary")
                .requirements(List.of("r1", "r2"))
                .constraints(List.of("c1"))
                .nonGoals(List.of("n1"))
                .risks(List.of("risk1"))
                .dependencies(List.of("dep1"))
                .build();
    }
}
