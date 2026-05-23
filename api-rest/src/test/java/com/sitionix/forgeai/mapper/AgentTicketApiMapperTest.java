package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerArchitectHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.AnalyzerQaLeadHandoffDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeAffectedSurfaceDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeChangedFileDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.UnitTestSonarDTO;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
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
    private QaLeadCompletionTicketPayloadApiMapper qaLeadCompletionTicketPayloadApiMapper;

    @Mock
    private ImplementBeTicketPayloadApiMapper implementBeTicketPayloadApiMapper;

    @Mock
    private TestUnitTicketPayloadApiMapper testUnitTicketPayloadApiMapper;

    @Mock
    private TestItTicketPayloadApiMapper testItTicketPayloadApiMapper;

    @Mock
    private UnitTestCompletionTicketPayloadApiMapper unitTestCompletionTicketPayloadApiMapper;

    @Mock
    private ImplementFeTicketPayloadApiMapper implementFeTicketPayloadApiMapper;

    @Mock
    private ImplementFeCompletionTicketPayloadApiMapper implementFeCompletionTicketPayloadApiMapper;

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
    void givenCompleteImplementBeLaneRequestDTO_whenAsTestUnitTicket_thenMapFields() {
        //given
        final CompleteImplementBeLaneRequestDTO source = CompleteImplementBeLaneRequestDTO.builder().scope("automationservice-sox").build();
        final UUID ticketId = UUID.randomUUID();
        final TestUnitPayload payload = new TestUnitPayload();
        when(this.testUnitTicketPayloadApiMapper.asTestUnitPayload(source)).thenReturn(payload);

        //when
        final AgentTicket<TestUnitPayload> actual = this.agentTicketApiMapper.asTestUnitTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("automationservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.TEST_UNIT);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.testUnitTicketPayloadApiMapper).asTestUnitPayload(source);
    }

    @Test
    void givenCompleteImplementBeLaneRequestDTO_whenAsTestItTicket_thenMapFields() {
        //given
        final CompleteImplementBeLaneRequestDTO source = CompleteImplementBeLaneRequestDTO.builder().scope("automationservice-sox").build();
        final UUID ticketId = UUID.randomUUID();
        final TestItPayload payload = new TestItPayload();
        when(this.testItTicketPayloadApiMapper.asTestItPayload(source)).thenReturn(payload);

        //when
        final AgentTicket<TestItPayload> actual = this.agentTicketApiMapper.asTestItTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("automationservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.TEST_IT);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.testItTicketPayloadApiMapper).asTestItPayload(source);
    }

    @Test
    void givenCompleteQaLeadLaneRequestDTO_whenAsTestItTicket_thenMapFields() {
        //given
        final CompleteQaLeadLaneRequestDTO source = CompleteQaLeadLaneRequestDTO.builder().scope("automationservice-sox").build();
        final UUID ticketId = UUID.randomUUID();
        final TestItPayload payload = new TestItPayload();
        when(this.qaLeadCompletionTicketPayloadApiMapper.asTestItPayload(source)).thenReturn(payload);

        //when
        final AgentTicket<TestItPayload> actual = this.agentTicketApiMapper.asTestItTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("automationservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.TEST_IT);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.qaLeadCompletionTicketPayloadApiMapper).asTestItPayload(source);
    }

    @Test
    void givenCompleteQaLeadLaneRequestDTO_whenAsTestUnitTicket_thenMapFields() {
        //given
        final CompleteQaLeadLaneRequestDTO source = CompleteQaLeadLaneRequestDTO.builder().scope("backendforfrontendservice-sox").build();
        final UUID ticketId = UUID.randomUUID();
        final TestUnitPayload payload = new TestUnitPayload();
        when(this.qaLeadCompletionTicketPayloadApiMapper.asTestUnitPayload(source)).thenReturn(payload);

        //when
        final AgentTicket<TestUnitPayload> actual = this.agentTicketApiMapper.asTestUnitTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("backendforfrontendservice-sox");
        assertThat(actual.getAgent()).isEqualTo(Agent.TEST_UNIT);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.qaLeadCompletionTicketPayloadApiMapper).asTestUnitPayload(source);
    }

    @Test
    void givenCompleteUnitTestLaneRequestDTO_whenAsReviewerTicket_thenMapFields() {
        //given
        final UnitTestSonarDTO sonarDto = UnitTestSonarDTO.builder()
                .coveragePercent(82.25)
                .issues(4)
                .build();
        final CompleteUnitTestLaneRequestDTO source = CompleteUnitTestLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("unit tests completed")
                .affectedFiles(java.util.List.of("src/main/java/com/example/Foo.java"))
                .sonar(sonarDto)
                .build();
        final UUID ticketId = UUID.randomUUID();
        final ReviewerPayload payload = new ReviewerPayload(
                "Prepare reviewer execution context",
                "GLOBAL",
                "unit tests completed",
                java.util.List.of("src/main/java/com/example/Foo.java"),
                new UnitTestSonar(82.25, 4)
        );
        when(this.unitTestCompletionTicketPayloadApiMapper.asReviewerPayload(source)).thenReturn(payload);

        //when
        final AgentTicket<ReviewerPayload> actual = this.agentTicketApiMapper.asReviewerTicket(source, ticketId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
        assertThat(actual.getScope()).isEqualTo("GLOBAL");
        assertThat(actual.getAgent()).isEqualTo(Agent.REVIEWER);
        assertThat(actual.getPayload()).isEqualTo(payload);
        verify(this.unitTestCompletionTicketPayloadApiMapper).asReviewerPayload(source);
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
    void givenCompleteImplementFeLaneRequest_whenAsImplementFeCompletionTicket_thenMapFields() {
        //given
        final CompleteImplementFeLaneRequestDTO source = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend changes for assigned flow.")
                .changedFiles(java.util.List.of(ImplementFeChangedFileDTO.builder().path("a").reason("b").build()))
                .affectedSurfaces(java.util.List.of(ImplementFeAffectedSurfaceDTO.builder()
                        .type(ImplementFeAffectedSurfaceDTO.TypeEnum.PAGE)
                        .name("Agent details")
                        .summary("Updated user-facing behavior on the page.")
                        .build()))
                .uiBehavior(java.util.List.of("User can perform the assigned action from the updated UI."))
                .build();
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final ImplementFeCompletionPayload payload = ImplementFeCompletionPayload.builder().build();
        when(this.implementFeCompletionTicketPayloadApiMapper.asImplementFeCompletionPayload(source)).thenReturn(payload);

        //when
        final AgentTicket<ImplementFeCompletionPayload> actual = this.agentTicketApiMapper.asImplementFeCompletionTicket(source, ticketId, laneId);

        //then
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isEqualTo(laneId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CONSUMED);
        assertThat(actual.getScope()).isEqualTo("sitionix-spa");
        assertThat(actual.getAgent()).isEqualTo(Agent.IMPLEMENT_FE);
        assertThat(actual.getPayload()).isEqualTo(payload);
        assertThat(actual.getCreatedAt()).isNotNull();
        assertThat(actual.getUpdatedAt()).isNotNull();
        verify(this.implementFeCompletionTicketPayloadApiMapper).asImplementFeCompletionPayload(source);
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
