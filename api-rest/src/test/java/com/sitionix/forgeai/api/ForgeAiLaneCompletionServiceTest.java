package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadDataCheckDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadTestLaneRequirementsDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadUnitTestNoteDTO;
import com.sitionix.forgeai.api.usecase.CompleteApiLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteArchitectLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteItTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteQaLeadLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteUnitTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CompleteReviewerTask;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.mapper.ApiLaneEvidencePayloadApiMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgeAiLaneCompletionServiceTest {

    private ForgeAiLaneCompletionService service;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;
    @Mock
    private ApiLaneEvidencePayloadApiMapper apiLaneEvidencePayloadApiMapper;
    @Mock
    private CompleteAgentTasks completeAgentTasks;
    @Mock
    private LaneScopeValidator laneScopeValidator;
    @Mock
    private CompleteArchitectLaneOrchestrationUseCase completeArchitectLaneOrchestrationUseCase;
    @Mock
    private CompleteApiLaneOrchestrationUseCase completeApiLaneOrchestrationUseCase;
    @Mock
    private ValidateApiLaneEvidence validateApiLaneEvidence;
    @Mock
    private CompleteQaLeadLaneOrchestrationUseCase completeQaLeadLaneOrchestrationUseCase;
    @Mock
    private CompleteItTestLaneOrchestrationUseCase completeItTestLaneOrchestrationUseCase;
    @Mock
    private CompleteUnitTestLaneOrchestrationUseCase completeUnitTestLaneOrchestrationUseCase;
    @Mock
    private CompleteReviewerTask completeReviewerTaskUseCase;

    @BeforeEach
    void setUp() {
        this.service = new ForgeAiLaneCompletionService(
                this.agentTicketApiMapper,
                this.apiLaneEvidencePayloadApiMapper,
                this.completeAgentTasks,
                this.laneScopeValidator,
                this.completeArchitectLaneOrchestrationUseCase,
                this.completeApiLaneOrchestrationUseCase,
                this.validateApiLaneEvidence,
                this.completeQaLeadLaneOrchestrationUseCase,
                this.completeItTestLaneOrchestrationUseCase,
                this.completeUnitTestLaneOrchestrationUseCase,
                this.completeReviewerTaskUseCase
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.agentTicketApiMapper,
                this.apiLaneEvidencePayloadApiMapper,
                this.completeAgentTasks,
                this.laneScopeValidator,
                this.completeArchitectLaneOrchestrationUseCase,
                this.completeApiLaneOrchestrationUseCase,
                this.validateApiLaneEvidence,
                this.completeQaLeadLaneOrchestrationUseCase,
                this.completeItTestLaneOrchestrationUseCase,
                this.completeUnitTestLaneOrchestrationUseCase,
                this.completeReviewerTaskUseCase
        );
    }

    @Test
    void givenTicketId_whenCompleteReviewerLane_thenCompleteReviewerTask() {
        final UUID ticketId = UUID.randomUUID();
        when(this.completeReviewerTaskUseCase.complete(ticketId)).thenReturn(UUID.randomUUID());

        this.service.completeReviewerLane(ticketId);

        verify(this.completeReviewerTaskUseCase).complete(ticketId);
    }

    @Test
    void givenArchitectLaneRequest_whenCompleteArchitectLane_thenDelegateToOrchestration() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = CompleteArchitectLaneRequest.builder().build();

        this.service.completeArchitectLane(ticketId, laneId, request);

        verify(this.completeArchitectLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenApiLaneRequest_whenCompleteApiLane_thenValidateAndComplete() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteApiLaneRequest request = CompleteApiLaneRequest.builder().build();
        when(this.laneScopeValidator.validateApiCompletion(laneId)).thenReturn(true);

        this.service.completeApiLane(ticketId, laneId, request);

        verify(this.laneScopeValidator).validateApiCompletion(laneId);
        verify(this.apiLaneEvidencePayloadApiMapper).asApiLaneEvidencePayload(request);
        verify(this.validateApiLaneEvidence).validate(
                org.mockito.ArgumentMatchers.eq(laneId),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(this.completeApiLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenImplementBeLaneRequest_whenCompleteImplementBeLane_thenCompleteTasks() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementBeLaneRequestDTO request = CompleteImplementBeLaneRequestDTO.builder().scope("automationservice-sox").build();
        final AgentTicket<TestUnitPayload> testUnitTicket = AgentTicket.<TestUnitPayload>builder().build();
        final AgentTicket<TestItPayload> testItTicket = AgentTicket.<TestItPayload>builder().build();
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        this.service.completeImplementBeLane(ticketId, laneId, request);

        verify(this.laneScopeValidator).validateImplementBeCallbackScope(laneId, request.getScope());
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.<AgentTicket<? extends AgentTicketPayload>>of(testUnitTicket, testItTicket));
    }

    @Test
    void givenBackendQaLeadLaneRequest_whenCompleteQaLeadLane_thenDelegateToOrchestration() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteQaLeadLaneRequestDTO request = this.getBackendQaLeadRequest();

        this.service.completeQaLeadLane(ticketId, laneId, request);

        verify(this.completeQaLeadLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenImplementFeLaneRequest_whenCompleteImplementFeLane_thenCompleteTasks() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementFeLaneRequestDTO request = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend changes for assigned flow.")
                .build();
        final AgentTicket<TestUiPayload> testUiTicket = AgentTicket.<TestUiPayload>builder().build();
        when(this.agentTicketApiMapper.asTestUiTicket(request, ticketId)).thenReturn(testUiTicket);

        this.service.completeImplementFeLane(ticketId, laneId, request);

        verify(this.laneScopeValidator).validateImplementFeCallbackScope(laneId, request.getScope());
        verify(this.agentTicketApiMapper).asTestUiTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.<AgentTicket<? extends AgentTicketPayload>>of(testUiTicket));
    }

    @Test
    void givenUiTestLaneRequest_whenCompleteUiTestLane_thenCompleteTasks() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteUiTestLaneRequestDTO request = CompleteUiTestLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("UI tests completed")
                .build();

        this.service.completeUiTestLane(ticketId, laneId, request);

        verify(this.laneScopeValidator).validateTestUiCallbackScope(laneId, request.getScope());
        verify(this.completeAgentTasks).complete(laneId, List.of());
    }

    @Test
    void givenItTestLaneRequest_whenCompleteItTestLane_thenDelegateToOrchestration() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteItTestLaneRequestDTO request = CompleteItTestLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Completed integration tests for backend flow.")
                .coveredCases(List.of("Create agent action successfully"))
                .build();

        this.service.completeItTestLane(ticketId, laneId, request);

        verify(this.completeItTestLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenUnitTestLaneRequest_whenCompleteUnitTestLane_thenDelegateToOrchestration() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteUnitTestLaneRequestDTO request = CompleteUnitTestLaneRequestDTO.builder().scope("automationservice-sox").build();

        this.service.completeUnitTestLane(ticketId, laneId, request);

        verify(this.completeUnitTestLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenAnalyzerLaneRequest_whenCompleteAnalyzerLane_thenCompleteTasks() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteAnalyzerLaneRequestDTO request = mock(CompleteAnalyzerLaneRequestDTO.class);
        final AgentTicket<ArchitectPayload> architectTicket = AgentTicket.<ArchitectPayload>builder().build();
        final AgentTicket<QaLeadPayload> qaLeadTicket = AgentTicket.<QaLeadPayload>builder().build();
        when(this.agentTicketApiMapper.asArchitectTicket(request, ticketId)).thenReturn(architectTicket);
        when(this.agentTicketApiMapper.asQaLeadTicket(request, ticketId)).thenReturn(qaLeadTicket);

        this.service.completeAnalyzerLane(ticketId, laneId, request);

        verify(this.laneScopeValidator).validateAnalyzerCallbackScope(laneId, null, null);
        verify(this.agentTicketApiMapper).asArchitectTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asQaLeadTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.<AgentTicket<? extends AgentTicketPayload>>of(architectTicket, qaLeadTicket));
    }

    private CompleteQaLeadLaneRequestDTO getBackendQaLeadRequest() {
        return CompleteQaLeadLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Prepared QA context for backend testing.")
                .testLaneRequirements(QaLeadTestLaneRequirementsDTO.builder()
                        .unitTestRequired(true)
                        .integrationTestRequired(true)
                        .uiTestRequired(false)
                        .build())
                .integrationTestCases(List.of(this.getIntegrationTestCase()))
                .unitTestNotes(List.of(this.getUnitTestNote()))
                .build();
    }

    private QaLeadIntegrationTestCaseDTO getIntegrationTestCase() {
        return QaLeadIntegrationTestCaseDTO.builder()
                .title("Create agent action successfully")
                .flow(QaLeadIntegrationFlowDTO.builder()
                        .name("Create agent action")
                        .method(QaLeadIntegrationFlowDTO.MethodEnum.POST)
                        .path("/api/v1/agent-actions")
                        .build())
                .given(List.of("ticket exists"))
                .when(List.of("POST request submitted"))
                .then(List.of("response 200"))
                .dataChecks(List.of(QaLeadDataCheckDTO.builder()
                        .target("agent ticket persisted")
                        .expectation("created record")
                        .build()))
                .priority(QaLeadIntegrationTestCaseDTO.PriorityEnum.HIGH)
                .build();
    }

    private QaLeadUnitTestNoteDTO getUnitTestNote() {
        return QaLeadUnitTestNoteDTO.builder()
                .target("service summary mapping")
                .note("Summary must preserve QA handoff semantics.")
                .build();
    }
}
