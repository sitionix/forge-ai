package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTicketApiMapperTest {

    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private ArchitectTicketPayloadApiMapper architectTicketPayloadApiMapper;

    @Mock
    private QaLeadTicketPayloadApiMapper qaLeadTicketPayloadApiMapper;

    @BeforeEach
    void setUp() throws Exception {
        this.agentTicketApiMapper = new AgentTicketApiMapperImpl();
        this.setField(this.agentTicketApiMapper, "architectTicketPayloadApiMapper", this.architectTicketPayloadApiMapper);
        this.setField(this.agentTicketApiMapper, "qaLeadTicketPayloadApiMapper", this.qaLeadTicketPayloadApiMapper);
    }

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsArchitectTicket_thenMapFields() {
        //given
        final AnalyzerArchitectHandoffDTO architectHandoff = AnalyzerArchitectHandoffDTO.builder()
                .task("architect-task")
                .summary("architect-summary")
                .scope("automationservice-sox")
                .build();
        final CompleteAnalyzerLaneRequestDTO source = CompleteAnalyzerLaneRequestDTO.builder()
                .architectHandoff(architectHandoff)
                .build();
        final UUID ticketId = UUID.randomUUID();
        final ArchitectPayload architectPayload = ArchitectPayload.builder().build();
        when(this.architectTicketPayloadApiMapper.asArchitectPayload(architectHandoff)).thenReturn(architectPayload);

        //when
        final AgentTicket<ArchitectPayload> actual = this.agentTicketApiMapper.asArchitectTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isNull();
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("automationservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.ARCHITECT);
        assertThat(actual.getPayload()).isEqualTo(architectPayload);
        verify(this.architectTicketPayloadApiMapper).asArchitectPayload(architectHandoff);
    }

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsQaLeadTicket_thenMapFields() {
        //given
        final AnalyzerQaLeadHandoffDTO qaLeadHandoff = AnalyzerQaLeadHandoffDTO.builder()
                .task("qa-task")
                .summary("qa-summary")
                .scope("backendforfrontendservice-sox")
                .build();
        final CompleteAnalyzerLaneRequestDTO source = CompleteAnalyzerLaneRequestDTO.builder()
                .qaLeadHandoff(qaLeadHandoff)
                .build();
        final UUID ticketId = UUID.randomUUID();
        final QaLeadPayload qaLeadPayload = QaLeadPayload.builder().build();
        when(this.qaLeadTicketPayloadApiMapper.asQaLeadPayload(qaLeadHandoff)).thenReturn(qaLeadPayload);

        //when
        final AgentTicket<QaLeadPayload> actual = this.agentTicketApiMapper.asQaLeadTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isNull();
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("backendforfrontendservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.QA_LEAD);
        assertThat(actual.getPayload()).isEqualTo(qaLeadPayload);
        verify(this.qaLeadTicketPayloadApiMapper).asQaLeadPayload(qaLeadHandoff);
    }

    private void setField(final Object target, final String fieldName, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
