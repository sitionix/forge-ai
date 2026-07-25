package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementBeTicketPayloadApiMapperTest {

    private ImplementBeTicketPayloadApiMapper implementBeTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.implementBeTicketPayloadApiMapper = new ImplementBeTicketPayloadApiMapperImpl();
    }

    @Test
    void givenArchitectImplementationHandoff_whenAsImplementBePayload_thenMapFields() {
        //given
        final ArchitectImplementationHandoff source = ArchitectImplementationHandoff.builder()
                .task("be task")
                .scope("automationservice-sox")
                .summary("be summary")
                .requirements(List.of("r1"))
                .constraints(List.of("c1"))
                .nonGoals(List.of("n1"))
                .architectureDecision("decision")
                .dependencies(List.of("d1"))
                .acceptanceNotes(List.of("a1"))
                .risks(List.of("risk"))
                .build();

        //when
        final ImplementBePayload actual = this.implementBeTicketPayloadApiMapper.asImplementBePayload(source);

        //then
        assertThat(actual).isEqualTo(ImplementBePayload.builder()
                .task("be task")
                .scope("automationservice-sox")
                .summary("be summary")
                .requirements(Set.of("r1"))
                .constraints(Set.of("c1"))
                .nonGoals(Set.of("n1"))
                .architectureDecision("decision")
                .dependencies(Set.of("d1"))
                .acceptanceNotes(Set.of("a1"))
                .risks(Set.of("risk"))
                .build());
    }
}
