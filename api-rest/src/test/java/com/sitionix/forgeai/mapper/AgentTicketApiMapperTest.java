package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTicketApiMapperTest {

    @InjectMocks
    private AgentTicketApiMapperImpl agentTicketApiMapper;

    @Mock
    private ArchitectTicketPayloadApiMapper architectTicketPayloadApiMapper;

    @Mock
    private QaLeadTicketPayloadApiMapper qaLeadTicketPayloadApiMapper;

    @Mock
    private ImplementBeTicketPayloadApiMapper implementBeTicketPayloadApiMapper;

    @Mock
    private ImplementFeTicketPayloadApiMapper implementFeTicketPayloadApiMapper;

    @Mock
    private ApiTicketPayloadApiMapper apiTicketPayloadApiMapper;

    @Mock
    private EventTicketPayloadApiMapper eventTicketPayloadApiMapper;

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsArchitectTicket_thenMapFields() {
        //given
        final AnalyzerArchitectHandoffDTO architectHandoff = AnalyzerArchitectHandoffDTO.builder().scope("automationservice-sox").build();
        final CompleteAnalyzerLaneRequestDTO source = CompleteAnalyzerLaneRequestDTO.builder().architectHandoff(architectHandoff).build();
        final UUID ticketId = UUID.randomUUID();
        final ArchitectPayload architectPayload = ArchitectPayload.builder().build();
        when(this.architectTicketPayloadApiMapper.asArchitectPayload(architectHandoff)).thenReturn(architectPayload);

        //when
        final AgentTicket<ArchitectPayload> actual = this.agentTicketApiMapper.asArchitectTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("automationservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.ARCHITECT);
        assertThat(actual.getPayload()).isEqualTo(architectPayload);
        verify(this.architectTicketPayloadApiMapper).asArchitectPayload(architectHandoff);
    }

    @Test
    void givenCompleteAnalyzerLaneRequestDTO_whenAsQaLeadTicket_thenMapFields() {
        //given
        final AnalyzerQaLeadHandoffDTO qaLeadHandoff = AnalyzerQaLeadHandoffDTO.builder().scope("backendforfrontendservice-sox").build();
        final CompleteAnalyzerLaneRequestDTO source = CompleteAnalyzerLaneRequestDTO.builder().qaLeadHandoff(qaLeadHandoff).build();
        final UUID ticketId = UUID.randomUUID();
        final QaLeadPayload qaLeadPayload = QaLeadPayload.builder().build();
        when(this.qaLeadTicketPayloadApiMapper.asQaLeadPayload(qaLeadHandoff)).thenReturn(qaLeadPayload);

        //when
        final AgentTicket<QaLeadPayload> actual = this.agentTicketApiMapper.asQaLeadTicket(source, ticketId);

        //then
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("backendforfrontendservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.QA_LEAD);
        assertThat(actual.getPayload()).isEqualTo(qaLeadPayload);
        verify(this.qaLeadTicketPayloadApiMapper).asQaLeadPayload(qaLeadHandoff);
    }

    @Test
    void givenCompleteArchitectLaneRequest_whenAsImplementBeTicket_thenMapFields() {
        //given
        final ArchitectImplementationHandoff implementationHandoff = ArchitectImplementationHandoff.builder().scope("automationservice-sox").build();
        final CompleteArchitectLaneRequest source = CompleteArchitectLaneRequest.builder().implementationHandoff(implementationHandoff).build();
        final UUID ticketId = UUID.randomUUID();
        final ImplementBePayload payload = ImplementBePayload.builder().build();
        when(this.implementBeTicketPayloadApiMapper.asImplementBePayload(implementationHandoff)).thenReturn(payload);

        //when
        final AgentTicket<ImplementBePayload> actual = this.agentTicketApiMapper.asImplementBeTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("automationservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.IMPLEMENT_BE);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.implementBeTicketPayloadApiMapper).asImplementBePayload(implementationHandoff);
    }

    @Test
    void givenCompleteArchitectLaneRequest_whenAsImplementFeTicket_thenMapFields() {
        //given
        final ArchitectImplementationHandoff implementationHandoff = ArchitectImplementationHandoff.builder().scope("frontendservice-sox").build();
        final CompleteArchitectLaneRequest source = CompleteArchitectLaneRequest.builder().implementationHandoff(implementationHandoff).build();
        final UUID ticketId = UUID.randomUUID();
        final ImplementFePayload payload = ImplementFePayload.builder().build();
        when(this.implementFeTicketPayloadApiMapper.asImplementFePayload(implementationHandoff)).thenReturn(payload);

        //when
        final AgentTicket<ImplementFePayload> actual = this.agentTicketApiMapper.asImplementFeTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("frontendservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.IMPLEMENT_FE);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.implementFeTicketPayloadApiMapper).asImplementFePayload(implementationHandoff);
    }

    @Test
    void givenCompleteArchitectLaneRequest_whenAsApiTicket_thenMapFields() {
        //given
        final ArchitectApiRequest apiRequest = ArchitectApiRequest.builder().scope("GLOBAL").build();
        final CompleteArchitectLaneRequest source = CompleteArchitectLaneRequest.builder().apiRequest(apiRequest).build();
        final UUID ticketId = UUID.randomUUID();
        final ApiPayload payload = ApiPayload.builder().build();
        when(this.apiTicketPayloadApiMapper.asApiPayload(apiRequest)).thenReturn(payload);

        //when
        final AgentTicket<ApiPayload> actual = this.agentTicketApiMapper.asApiTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("GLOBAL");
        assertThat(actual.getAgent()).isEqualTo(Agent.API);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.apiTicketPayloadApiMapper).asApiPayload(apiRequest);
    }

    @Test
    void givenCompleteArchitectLaneRequest_whenAsEventTicket_thenMapFields() {
        //given
        final ArchitectEventRequest eventRequest = ArchitectEventRequest.builder().scope("GLOBAL").build();
        final CompleteArchitectLaneRequest source = CompleteArchitectLaneRequest.builder().eventRequest(eventRequest).build();
        final UUID ticketId = UUID.randomUUID();
        final EventPayload payload = EventPayload.builder().build();
        when(this.eventTicketPayloadApiMapper.asEventPayload(eventRequest)).thenReturn(payload);

        //when
        final AgentTicket<EventPayload> actual = this.agentTicketApiMapper.asEventTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("GLOBAL");
        assertThat(actual.getAgent()).isEqualTo(Agent.EVENT);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.eventTicketPayloadApiMapper).asEventPayload(eventRequest);
    }
}
