package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.ApiLaneContractResult;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.sitionix.forgeai.api.usecase.CompleteApiLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteArchitectLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteItTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteQaLeadLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteUnitTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForgeAiLaneCompletionService {

    private final AgentTicketApiMapper agentTicketApiMapper;
    private final ApiLaneEvidencePayloadApiMapper apiLaneEvidencePayloadApiMapper;
    private final CompleteAgentTasks completeAgentTasks;
    private final LaneScopeValidator laneScopeValidator;
    private final CompleteArchitectLaneOrchestrationUseCase completeArchitectLaneOrchestrationUseCase;
    private final CompleteApiLaneOrchestrationUseCase completeApiLaneOrchestrationUseCase;
    private final ValidateApiLaneEvidence validateApiLaneEvidence;
    private final CompleteQaLeadLaneOrchestrationUseCase completeQaLeadLaneOrchestrationUseCase;
    private final CompleteItTestLaneOrchestrationUseCase completeItTestLaneOrchestrationUseCase;
    private final CompleteUnitTestLaneOrchestrationUseCase completeUnitTestLaneOrchestrationUseCase;
    private final CompleteReviewerTask completeReviewerTaskUseCase;

    public void completeAnalyzerLane(final UUID ticketId,
                                     final UUID laneId,
                                     final CompleteAnalyzerLaneRequestDTO request) {
        log.info("Received completeAnalyzerLane request for ticketId: {}, laneId: {}, with request body: {}", ticketId, laneId, request);
        this.laneScopeValidator.validateAnalyzerCallbackScope(
                laneId,
                request.getArchitectHandoff() == null ? null : request.getArchitectHandoff().getScope(),
                request.getQaLeadHandoff() == null ? null : request.getQaLeadHandoff().getScope()
        );

        final AgentTicket<ArchitectPayload> architectTicket = this.agentTicketApiMapper.asArchitectTicket(request, ticketId);
        final AgentTicket<QaLeadPayload> qaLeadTicket = this.agentTicketApiMapper.asQaLeadTicket(request, ticketId);
        this.completeAgentTasks.complete(laneId, List.of(architectTicket, qaLeadTicket));
    }

    public void completeArchitectLane(final UUID ticketId,
                                      final UUID laneId,
                                      final CompleteArchitectLaneRequest request) {
        log.info("Received completeArchitectLane request for ticketId: {}, laneId: {}, with request body: {}", ticketId, laneId, request);
        this.completeArchitectLaneOrchestrationUseCase.complete(ticketId, laneId, request);
    }

    public void completeApiLane(final UUID ticketId,
                                final UUID laneId,
                                final CompleteApiLaneRequest request) {
        log.info("Received completeApiLane request for ticketId: {}, laneId: {}, with request body: {}", ticketId, laneId, request);
        final boolean apiLaneRequiresCompletion = this.laneScopeValidator.validateApiCompletion(laneId);
        if (apiLaneRequiresCompletion) {
            final Set<String> callbackScopes = request.getContracts() == null
                    ? Set.of()
                    : request.getContracts().stream()
                    .filter(Objects::nonNull)
                    .map(ApiLaneContractResult::getScope)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            this.validateApiLaneEvidence.validate(laneId, callbackScopes, this.apiLaneEvidencePayloadApiMapper.asApiLaneEvidencePayload(request));
            this.completeApiLaneOrchestrationUseCase.complete(ticketId, laneId, request);
        }
    }

    public void completeImplementBeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final CompleteImplementBeLaneRequestDTO request) {
        log.info("Received completeImplementBeLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, request);
        this.laneScopeValidator.validateImplementBeCallbackScope(laneId, request.getScope());
        final AgentTicket<TestUnitPayload> testUnitTicket = this.agentTicketApiMapper.asTestUnitTicket(request, ticketId);
        final AgentTicket<TestItPayload> testItTicket = this.agentTicketApiMapper.asTestItTicket(request, ticketId);
        this.completeAgentTasks.complete(laneId, List.of(testUnitTicket, testItTicket));
    }

    public void completeImplementFeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final CompleteImplementFeLaneRequestDTO request) {
        log.info("Received completeImplementFeLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, request);
        this.laneScopeValidator.validateImplementFeCallbackScope(laneId, request.getScope());
        final AgentTicket<TestUiPayload> testUiTicket = this.agentTicketApiMapper.asTestUiTicket(request, ticketId);
        this.completeAgentTasks.complete(laneId, List.of(testUiTicket));
    }

    public void completeQaLeadLane(final UUID ticketId,
                                   final UUID laneId,
                                   final CompleteQaLeadLaneRequestDTO request) {
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, request);
    }

    public void completeItTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final CompleteItTestLaneRequestDTO request) {
        this.completeItTestLaneOrchestrationUseCase.complete(ticketId, laneId, request);
    }

    public void completeUiTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final CompleteUiTestLaneRequestDTO request) {
        log.info("Received completeUiTestLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, request);
        this.laneScopeValidator.validateTestUiCallbackScope(laneId, request.getScope());
        this.completeAgentTasks.complete(laneId, List.of());
    }

    public void completeUnitTestLane(final UUID ticketId,
                                     final UUID laneId,
                                     final CompleteUnitTestLaneRequestDTO request) {
        log.info("Received completeUnitTestLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, request);
        this.completeUnitTestLaneOrchestrationUseCase.complete(ticketId, laneId, request);
    }

    public void completeReviewerLane(final UUID ticketId) {
        this.completeReviewerTaskUseCase.complete(ticketId);
    }
}
