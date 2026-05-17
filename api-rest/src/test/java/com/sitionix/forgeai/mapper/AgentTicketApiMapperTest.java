package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AgentDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTicketApiMapperTest {

    private AgentTicketApiMapper agentTicketApiMapper;

    @BeforeEach
    void setUp() throws Exception {
        this.agentTicketApiMapper = new AgentTicketApiMapperImpl();
        this.setField(this.agentTicketApiMapper, "architectTicketPayloadApiMapper", new ArchitectTicketPayloadApiMapperImpl());
        this.setField(this.agentTicketApiMapper, "qaLeadTicketPayloadApiMapper", new QaLeadTicketPayloadApiMapperImpl());
    }

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsArchitectTicket_thenMapFields() {
        //given
        final CompleteAnalyzerLaneRequestDTO source = this.getRequest();
        final UUID ticketId = UUID.randomUUID();

        //when
        final AgentTicket<ArchitectPayload> actual = this.agentTicketApiMapper.asArchitectTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isNull();
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getPayload().getTask()).isEqualTo("architect-task");
        assertThat(actual.getPayload().getScope()).isEqualTo("automationservice-sox");
    }

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsQaLeadTicket_thenMapFields() {
        //given
        final CompleteAnalyzerLaneRequestDTO source = this.getRequest();
        final UUID ticketId = UUID.randomUUID();

        //when
        final AgentTicket<QaLeadPayload> actual = this.agentTicketApiMapper.asQaLeadTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isNull();
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getPayload().getTask()).isEqualTo("qa-task");
        assertThat(actual.getPayload().getScope()).isEqualTo("automationservice-sox");
        assertThat(actual.getPayload().getRequirements()).containsExactly("scope-r1");
    }

    private CompleteAnalyzerLaneRequestDTO getRequest() {
        return CompleteAnalyzerLaneRequestDTO.builder()
                .summary("summary")
                .architectHandoff(AnalyzerArchitectHandoffDTO.builder()
                        .writtenBy(AgentDTO.ANALYZER)
                        .task("architect-task")
                        .scope("automationservice-sox")
                        .summary("architect-summary")
                        .requirements(List.of("r1"))
                        .constraints(List.of("c1"))
                        .nonGoals(List.of("n1"))
                        .risks(List.of("risk1"))
                        .dependencies(List.of("dep1"))
                        .build())
                .qaLeadHandoff(AnalyzerQaLeadHandoffDTO.builder()
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
                        .build())
                .build();
    }

    private void setField(final Object target, final String fieldName, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
