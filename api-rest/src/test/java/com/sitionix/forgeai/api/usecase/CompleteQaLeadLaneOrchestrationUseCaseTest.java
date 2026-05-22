package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadTestLaneRequirementsDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteQaLeadLaneOrchestrationUseCaseTest {

    private CompleteQaLeadLaneOrchestrationUseCase completeQaLeadLaneOrchestrationUseCase;

    @Mock
    private LaneScopeValidator laneScopeValidator;

    @Mock
    private CreateAgentTask createAgentTask;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @BeforeEach
    void setUp() {
        this.completeQaLeadLaneOrchestrationUseCase = new CompleteQaLeadLaneOrchestrationUseCase(
                this.laneScopeValidator,
                this.createAgentTask,
                this.agentTicketApiMapper
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.laneScopeValidator,
                this.createAgentTask,
                this.agentTicketApiMapper
        );
    }

    @Test
    void givenBackendScopeAndUnitAndIntegrationRequired_whenComplete_thenCreateTestUnitAndTestItTickets() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteQaLeadLaneRequestDTO request = this.getRequest(true, true, false, List.of(this.getIntegrationTestCase()));
        final AgentTicket<TestUnitPayload> testUnitTicket = mock(AgentTicket.class);
        final AgentTicket<TestItPayload> testItTicket = mock(AgentTicket.class);
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateQaLeadCompletion(ticketId, laneId, "automationservice-sox");
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.createAgentTask).create(testUnitTicket, laneId);
        verify(this.createAgentTask).create(testItTicket, laneId);
    }

    @Test
    void givenBackendScopeAndIntegrationNotRequired_whenComplete_thenMarkIntegrationLaneNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteQaLeadLaneRequestDTO request = this.getRequest(true, false, false, List.of());
        final AgentTicket<TestUnitPayload> testUnitTicket = mock(AgentTicket.class);
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateQaLeadCompletion(ticketId, laneId, "automationservice-sox");
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.createAgentTask).create(testUnitTicket, laneId);
        verify(this.createAgentTask).markAsNotNeeded(laneId, "automationservice-sox", Agent.TEST_IT);
    }

    @Test
    void givenBackendScopeAndUnitNotRequired_whenComplete_thenMarkUnitLaneNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final QaLeadIntegrationTestCaseDTO integrationTestCase = this.getIntegrationTestCase();
        final CompleteQaLeadLaneRequestDTO request = this.getRequest(false, true, false, List.of(integrationTestCase));
        final AgentTicket<TestItPayload> testItTicket = mock(AgentTicket.class);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateQaLeadCompletion(ticketId, laneId, "automationservice-sox");
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.createAgentTask).markAsNotNeeded(laneId, "automationservice-sox", Agent.TEST_UNIT);
        verify(this.createAgentTask).create(testItTicket, laneId);
    }

    @Test
    void givenBackendScopeAndBothTestsNotRequired_whenComplete_thenMarkBothBackendTestLanesNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteQaLeadLaneRequestDTO request = this.getRequest(false, false, false, List.of());

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateQaLeadCompletion(ticketId, laneId, "automationservice-sox");
        verify(this.createAgentTask).markAsNotNeeded(laneId, "automationservice-sox", Agent.TEST_UNIT);
        verify(this.createAgentTask).markAsNotNeeded(laneId, "automationservice-sox", Agent.TEST_IT);
    }

    @Test
    void givenIntegrationRequiredWithoutCases_whenComplete_thenThrowResponseStatusException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteQaLeadLaneRequestDTO request = this.getRequest(true, true, false, List.of());

        //when //then
        assertThatThrownBy(() -> this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("integrationTestCases must not be empty");
        verifyNoMoreInteractions(this.laneScopeValidator, this.createAgentTask, this.agentTicketApiMapper);
    }

    private CompleteQaLeadLaneRequestDTO getRequest(final boolean unitTestRequired,
                                                     final boolean integrationTestRequired,
                                                     final boolean uiTestRequired,
                                                     final List<QaLeadIntegrationTestCaseDTO> integrationTestCases) {
        return CompleteQaLeadLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Prepared QA context for backend testing.")
                .testLaneRequirements(QaLeadTestLaneRequirementsDTO.builder()
                        .unitTestRequired(unitTestRequired)
                        .integrationTestRequired(integrationTestRequired)
                        .uiTestRequired(uiTestRequired)
                        .build())
                .integrationTestCases(integrationTestCases)
                .build();
    }

    private QaLeadIntegrationTestCaseDTO getIntegrationTestCase() {
        return QaLeadIntegrationTestCaseDTO.builder()
                .title("Create agent action successfully")
                .build();
    }
}
