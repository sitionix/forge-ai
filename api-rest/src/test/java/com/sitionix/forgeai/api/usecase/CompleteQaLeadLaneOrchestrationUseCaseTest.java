package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadTestLaneRequirementsDTO;
import com.sitionix.forgeai.api.LaneCompletionValidator;
import com.sitionix.forgeai.api.RequestValidationException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteQaLeadLaneOrchestrationUseCaseTest {

    private CompleteQaLeadLaneOrchestrationUseCase completeQaLeadLaneOrchestrationUseCase;

    @Mock
    private LaneCompletionValidator laneCompletionValidator;

    @Mock
    private CreateAgentTask createAgentTask;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @BeforeEach
    void setUp() {
        this.completeQaLeadLaneOrchestrationUseCase = new CompleteQaLeadLaneOrchestrationUseCase(
                this.laneCompletionValidator,
                this.createAgentTask,
                this.agentTicketApiMapper
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.laneCompletionValidator,
                this.createAgentTask,
                this.agentTicketApiMapper
        );
    }

    @Test
    void givenBackendScopeAndUnitAndIntegrationRequired_whenComplete_thenCreateTestUnitAndTestItTickets() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final String scope = "automationservice-sox";
        final CompleteQaLeadLaneRequestDTO request = mock(CompleteQaLeadLaneRequestDTO.class);
        final QaLeadTestLaneRequirementsDTO requirements = mock(QaLeadTestLaneRequirementsDTO.class);
        final QaLeadIntegrationTestCaseDTO integrationTestCase = mock(QaLeadIntegrationTestCaseDTO.class);
        final AgentTicket<TestUnitPayload> testUnitTicket = mock(AgentTicket.class);
        final AgentTicket<TestItPayload> testItTicket = mock(AgentTicket.class);
        when(request.getScope()).thenReturn(scope);
        when(request.getTestLaneRequirements()).thenReturn(requirements);
        when(requirements.getUnitTestRequired()).thenReturn(Boolean.TRUE);
        when(requirements.getIntegrationTestRequired()).thenReturn(Boolean.TRUE);
        when(request.getIntegrationTestCases()).thenReturn(List.of(integrationTestCase));
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneCompletionValidator).validateQaLeadCompletion(ticketId, laneId, scope);
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
        final String scope = "automationservice-sox";
        final CompleteQaLeadLaneRequestDTO request = mock(CompleteQaLeadLaneRequestDTO.class);
        final QaLeadTestLaneRequirementsDTO requirements = mock(QaLeadTestLaneRequirementsDTO.class);
        final AgentTicket<TestUnitPayload> testUnitTicket = mock(AgentTicket.class);
        when(request.getScope()).thenReturn(scope);
        when(request.getTestLaneRequirements()).thenReturn(requirements);
        when(requirements.getUnitTestRequired()).thenReturn(Boolean.TRUE);
        when(requirements.getIntegrationTestRequired()).thenReturn(Boolean.FALSE);
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneCompletionValidator).validateQaLeadCompletion(ticketId, laneId, scope);
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.createAgentTask).create(testUnitTicket, laneId);
        verify(this.createAgentTask).markAsNotNeeded(laneId, scope, Agent.TEST_IT);
    }

    @Test
    void givenBackendScopeAndUnitNotRequired_whenComplete_thenMarkUnitLaneNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final String scope = "automationservice-sox";
        final CompleteQaLeadLaneRequestDTO request = mock(CompleteQaLeadLaneRequestDTO.class);
        final QaLeadTestLaneRequirementsDTO requirements = mock(QaLeadTestLaneRequirementsDTO.class);
        final QaLeadIntegrationTestCaseDTO integrationTestCase = mock(QaLeadIntegrationTestCaseDTO.class);
        final AgentTicket<TestItPayload> testItTicket = mock(AgentTicket.class);
        when(request.getScope()).thenReturn(scope);
        when(request.getTestLaneRequirements()).thenReturn(requirements);
        when(requirements.getUnitTestRequired()).thenReturn(Boolean.FALSE);
        when(requirements.getIntegrationTestRequired()).thenReturn(Boolean.TRUE);
        when(request.getIntegrationTestCases()).thenReturn(List.of(integrationTestCase));
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneCompletionValidator).validateQaLeadCompletion(ticketId, laneId, scope);
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.createAgentTask).markAsNotNeeded(laneId, scope, Agent.TEST_UNIT);
        verify(this.createAgentTask).create(testItTicket, laneId);
    }

    @Test
    void givenBackendScopeAndBothTestsNotRequired_whenComplete_thenMarkBothBackendTestLanesNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final String scope = "automationservice-sox";
        final CompleteQaLeadLaneRequestDTO request = mock(CompleteQaLeadLaneRequestDTO.class);
        final QaLeadTestLaneRequirementsDTO requirements = mock(QaLeadTestLaneRequirementsDTO.class);
        when(request.getScope()).thenReturn(scope);
        when(request.getTestLaneRequirements()).thenReturn(requirements);
        when(requirements.getUnitTestRequired()).thenReturn(Boolean.FALSE);
        when(requirements.getIntegrationTestRequired()).thenReturn(Boolean.FALSE);

        //when
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneCompletionValidator).validateQaLeadCompletion(ticketId, laneId, scope);
        verify(this.createAgentTask).markAsNotNeeded(laneId, scope, Agent.TEST_UNIT);
        verify(this.createAgentTask).markAsNotNeeded(laneId, scope, Agent.TEST_IT);
    }

    @Test
    void givenIntegrationRequiredWithoutCases_whenComplete_thenThrowRequestValidationException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final String scope = "automationservice-sox";
        final CompleteQaLeadLaneRequestDTO request = mock(CompleteQaLeadLaneRequestDTO.class);
        final QaLeadTestLaneRequirementsDTO requirements = mock(QaLeadTestLaneRequirementsDTO.class);
        when(request.getScope()).thenReturn(scope);
        when(request.getTestLaneRequirements()).thenReturn(requirements);
        when(requirements.getIntegrationTestRequired()).thenReturn(Boolean.TRUE);
        when(request.getIntegrationTestCases()).thenReturn(List.of());

        //when //then
        assertThatThrownBy(() -> this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("integrationTestCases must not be empty");

        verify(this.laneCompletionValidator).validateQaLeadCompletion(ticketId, laneId, scope);
    }
}
