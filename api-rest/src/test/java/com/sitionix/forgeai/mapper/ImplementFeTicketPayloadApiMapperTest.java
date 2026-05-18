package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementFeTicketPayloadApiMapperTest {

    private ImplementFeTicketPayloadApiMapper implementFeTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.implementFeTicketPayloadApiMapper = new ImplementFeTicketPayloadApiMapperImpl();
    }

    @Test
    void givenArchitectImplementationHandoff_whenAsImplementFePayload_thenMapFields() {
        //given
        final ArchitectImplementationHandoff source = ArchitectImplementationHandoff.builder()
                .task("fe task")
                .scope("frontendservice-sox")
                .summary("fe summary")
                .requirements(List.of("r1"))
                .constraints(List.of("c1"))
                .nonGoals(List.of("n1"))
                .architectureDecision("decision")
                .dependencies(List.of("d1"))
                .acceptanceNotes(List.of("a1"))
                .risks(List.of("risk"))
                .build();

        //when
        final ImplementFePayload actual = this.implementFeTicketPayloadApiMapper.asImplementFePayload(source);

        //then
        assertThat(actual).isEqualTo(ImplementFePayload.builder()
                .task("fe task")
                .scope("frontendservice-sox")
                .summary("fe summary")
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
