package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTicketApiMapperTest {

    private AgentTicketApiMapper agentTicketApiMapper;
    private ArchitectTicketPayloadApiMapperStub architectTicketPayloadApiMapper;
    private QaLeadTicketPayloadApiMapperStub qaLeadTicketPayloadApiMapper;

    @BeforeEach
    void setUp() throws Exception {
        this.architectTicketPayloadApiMapper = new ArchitectTicketPayloadApiMapperStub();
        this.qaLeadTicketPayloadApiMapper = new QaLeadTicketPayloadApiMapperStub();
        this.agentTicketApiMapper = new AgentTicketApiMapperImpl();
        this.setField(this.agentTicketApiMapper, "architectTicketPayloadApiMapper", this.architectTicketPayloadApiMapper);
        this.setField(this.agentTicketApiMapper, "qaLeadTicketPayloadApiMapper", this.qaLeadTicketPayloadApiMapper);
    }

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsArchitectTicket_thenMapFields() {
        //given
        final AnalyzerArchitectHandoffDTO architectHandoff = AnalyzerArchitectHandoffDTO.builder().build();
        final CompleteAnalyzerLaneRequestDTO source = CompleteAnalyzerLaneRequestDTO.builder()
                .architectHandoff(architectHandoff)
                .build();
        final UUID ticketId = UUID.randomUUID();

        //when
        final AgentTicket<ArchitectPayload> actual = this.agentTicketApiMapper.asArchitectTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isNull();
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getPayload()).isEqualTo(this.architectTicketPayloadApiMapper.expected);
        assertThat(this.architectTicketPayloadApiMapper.captured).isEqualTo(architectHandoff);
    }

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsQaLeadTicket_thenMapFields() {
        //given
        final AnalyzerQaLeadHandoffDTO qaLeadHandoff = AnalyzerQaLeadHandoffDTO.builder().build();
        final CompleteAnalyzerLaneRequestDTO source = CompleteAnalyzerLaneRequestDTO.builder()
                .qaLeadHandoff(qaLeadHandoff)
                .build();
        final UUID ticketId = UUID.randomUUID();

        //when
        final AgentTicket<QaLeadPayload> actual = this.agentTicketApiMapper.asQaLeadTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isNull();
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getPayload()).isEqualTo(this.qaLeadTicketPayloadApiMapper.expected);
        assertThat(this.qaLeadTicketPayloadApiMapper.captured).isEqualTo(qaLeadHandoff);
    }

    private void setField(final Object target, final String fieldName, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class ArchitectTicketPayloadApiMapperStub implements ArchitectTicketPayloadApiMapper {
        private final ArchitectPayload expected = ArchitectPayload.builder().build();
        private AnalyzerArchitectHandoffDTO captured;

        @Override
        public ArchitectPayload asArchitectPayload(final AnalyzerArchitectHandoffDTO source) {
            this.captured = source;
            return this.expected;
        }
    }

    private static class QaLeadTicketPayloadApiMapperStub implements QaLeadTicketPayloadApiMapper {
        private final QaLeadPayload expected = QaLeadPayload.builder().build();
        private AnalyzerQaLeadHandoffDTO captured;

        @Override
        public QaLeadPayload asQaLeadPayload(final AnalyzerQaLeadHandoffDTO source) {
            this.captured = source;
            return this.expected;
        }
    }
}
