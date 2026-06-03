package com.sitionix.forgeai.it.infra;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneContractResult;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneGeneratedArtifact;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionCommands;
import com.sitionix.forgeai.domain.usecase.CompleteLaneCallbacks;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.mapper.ApiLaneEvidencePayloadApiMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class LaneCompletionTestFacade {

    private final CompleteLaneCallbacks completion;
    private final CompletionRequestFixtureLoader fixtureLoader;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final ApiLaneEvidencePayloadApiMapper apiLaneEvidencePayloadApiMapper;

    public LaneCompletionTestFacade(final CompleteLaneCallbacks completion,
                                    final CompletionRequestFixtureLoader fixtureLoader,
                                    final AgentTicketApiMapper agentTicketApiMapper,
                                    final ApiLaneEvidencePayloadApiMapper apiLaneEvidencePayloadApiMapper) {
        this.completion = completion;
        this.fixtureLoader = fixtureLoader;
        this.agentTicketApiMapper = agentTicketApiMapper;
        this.apiLaneEvidencePayloadApiMapper = apiLaneEvidencePayloadApiMapper;
    }

    public void completeAnalyzerLane(final UUID ticketId, final UUID laneId) {
        this.completeAnalyzerLane(ticketId, laneId, request -> {
        });
    }

    public void completeAnalyzerLane(final UUID ticketId,
                                     final UUID laneId,
                                     final Consumer<CompleteAnalyzerLaneRequestDTO> mutator) {
        final CompleteAnalyzerLaneRequestDTO request = this.fixtureLoader.read("requestCompleteAnalyzerLane.json", CompleteAnalyzerLaneRequestDTO.class, mutator);
        this.completion.completeAnalyzerLane(new LaneCompletionCommands.Analyzer(
                ticketId,
                laneId,
                request.getArchitectHandoff() == null ? null : request.getArchitectHandoff().getScope(),
                request.getQaLeadHandoff() == null ? null : request.getQaLeadHandoff().getScope(),
                this.agentTicketApiMapper.asArchitectTicket(request, ticketId),
                this.agentTicketApiMapper.asQaLeadTicket(request, ticketId)
        ));
    }

    public void completeArchitectLane(final UUID ticketId, final UUID laneId) {
        this.completeArchitectLane(ticketId, laneId, "requestCompleteArchitectLane.json", request -> {
        });
    }

    public void completeArchitectLane(final UUID ticketId,
                                      final UUID laneId,
                                      final String fixture,
                                      final Consumer<CompleteArchitectLaneRequest> mutator) {
        final CompleteArchitectLaneRequest request = this.fixtureLoader.read(fixture, CompleteArchitectLaneRequest.class, mutator);
        this.completion.completeArchitectLane(new LaneCompletionCommands.Architect(
                ticketId,
                laneId,
                request.getImplementationHandoff().getScope(),
                this.agentTicketApiMapper.asImplementBeTicket(request, ticketId),
                this.agentTicketApiMapper.asImplementFeTicket(request, ticketId),
                this.shouldCreateApiTask(request) ? this.agentTicketApiMapper.asApiTicket(request, ticketId) : null,
                this.shouldCreateEventTask(request) ? this.agentTicketApiMapper.asEventTicket(request, ticketId) : null
        ));
    }

    public void completeApiLane(final UUID ticketId, final UUID laneId) {
        this.completeApiLane(ticketId, laneId, "requestCompleteApiLane.json", request -> {
        });
    }

    public void completeApiLane(final UUID ticketId,
                                final UUID laneId,
                                final Consumer<CompleteApiLaneRequest> mutator) {
        this.completeApiLane(ticketId, laneId, "requestCompleteApiLane.json", mutator);
    }

    public void completeApiLane(final UUID ticketId,
                                final UUID laneId,
                                final String fixture,
                                final Consumer<CompleteApiLaneRequest> mutator) {
        final CompleteApiLaneRequest request = this.fixtureLoader.read(fixture, CompleteApiLaneRequest.class, mutator);
        this.completion.completeApiLane(new LaneCompletionCommands.Api(
                ticketId,
                laneId,
                request.getSummary(),
                this.apiLaneEvidencePayloadApiMapper.asApiLaneEvidencePayload(request),
                request.getContracts() == null ? List.of() : request.getContracts().stream().map(this::asContractResult).toList()
        ));
    }

    public void completeImplementBeLane(final UUID ticketId, final UUID laneId) {
        this.completeImplementBeLane(ticketId, laneId, request -> {
        });
    }

    public void completeImplementBeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final Consumer<CompleteImplementBeLaneRequestDTO> mutator) {
        final CompleteImplementBeLaneRequestDTO request = this.fixtureLoader.read("requestCompleteImplementBeLane.json", CompleteImplementBeLaneRequestDTO.class, mutator);
        this.completion.completeImplementBeLane(new LaneCompletionCommands.ImplementBe(
                ticketId,
                laneId,
                request.getScope(),
                this.agentTicketApiMapper.asTestUnitTicket(request, ticketId),
                this.agentTicketApiMapper.asTestItTicket(request, ticketId)
        ));
    }

    public void completeImplementFeLane(final UUID ticketId, final UUID laneId) {
        this.completeImplementFeLane(ticketId, laneId, request -> {
        });
    }

    public void completeImplementFeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final Consumer<CompleteImplementFeLaneRequestDTO> mutator) {
        final CompleteImplementFeLaneRequestDTO request = this.fixtureLoader.read("requestCompleteImplementFeLane.json", CompleteImplementFeLaneRequestDTO.class, mutator);
        this.completion.completeImplementFeLane(new LaneCompletionCommands.ImplementFe(
                ticketId,
                laneId,
                request.getScope(),
                this.agentTicketApiMapper.asTestUiTicket(request, ticketId)
        ));
    }

    public void completeQaLeadLaneBackend(final UUID ticketId, final UUID laneId) {
        this.completeQaLeadLaneBackend(ticketId, laneId, request -> {
        });
    }

    public void completeQaLeadLaneBackend(final UUID ticketId,
                                          final UUID laneId,
                                          final Consumer<CompleteQaLeadLaneRequestDTO> mutator) {
        final CompleteQaLeadLaneRequestDTO request = this.fixtureLoader.read("requestCompleteQaLeadLaneBackend.json", CompleteQaLeadLaneRequestDTO.class, mutator);
        this.completion.completeQaLeadLane(this.asQaLeadCommand(ticketId, laneId, request));
    }

    public void completeQaLeadLaneBackendNotRequired(final UUID ticketId, final UUID laneId) {
        final CompleteQaLeadLaneRequestDTO request = this.fixtureLoader.read("requestCompleteQaLeadLaneBackendNotRequired.json", CompleteQaLeadLaneRequestDTO.class);
        this.completion.completeQaLeadLane(this.asQaLeadCommand(ticketId, laneId, request));
    }

    public void completeItTestLane(final UUID ticketId, final UUID laneId) {
        this.completeItTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeItTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final Consumer<CompleteItTestLaneRequestDTO> mutator) {
        final CompleteItTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteItTestLane.json", CompleteItTestLaneRequestDTO.class, mutator);
        this.completion.completeItTestLane(new LaneCompletionCommands.ItTest(
                ticketId,
                laneId,
                request.getScope(),
                this.agentTicketApiMapper.asTestItCompletionTicket(request, ticketId, laneId)
        ));
    }

    public void completeUiTestLane(final UUID ticketId, final UUID laneId) {
        this.completeUiTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeUiTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final Consumer<CompleteUiTestLaneRequestDTO> mutator) {
        final CompleteUiTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteUiTestLane.json", CompleteUiTestLaneRequestDTO.class, mutator);
        this.completion.completeUiTestLane(new LaneCompletionCommands.UiTest(ticketId, laneId, request.getScope()));
    }

    public void completeUnitTestLane(final UUID ticketId, final UUID laneId) {
        this.completeUnitTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeUnitTestLane(final UUID ticketId,
                                     final UUID laneId,
                                     final Consumer<CompleteUnitTestLaneRequestDTO> mutator) {
        final CompleteUnitTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteUnitTestLane.json", CompleteUnitTestLaneRequestDTO.class, mutator);
        this.completion.completeUnitTestLane(new LaneCompletionCommands.UnitTest(
                ticketId,
                laneId,
                request.getScope(),
                this.agentTicketApiMapper.asReviewerTicket(request, ticketId)
        ));
    }

    public void completeReviewerLane(final UUID ticketId) {
        this.completion.completeReviewerLane(new LaneCompletionCommands.Reviewer(ticketId));
    }

    private LaneCompletionCommands.QaLead asQaLeadCommand(final UUID ticketId,
                                                          final UUID laneId,
                                                          final CompleteQaLeadLaneRequestDTO request) {
        return new LaneCompletionCommands.QaLead(
                ticketId,
                laneId,
                request.getScope(),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired()),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired()),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getUiTestRequired()),
                this.agentTicketApiMapper.asTestUnitTicket(request, ticketId),
                this.agentTicketApiMapper.asTestItTicket(request, ticketId),
                this.agentTicketApiMapper.asTestUiTicket(request, ticketId)
        );
    }

    private boolean shouldCreateApiTask(final CompleteArchitectLaneRequest request) {
        if (request.getApiRequest() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(request.getApiRequest().getRequired())) {
            return true;
        }
        return request.getApiRequest().getOperations() != null && !request.getApiRequest().getOperations().isEmpty();
    }

    private boolean shouldCreateEventTask(final CompleteArchitectLaneRequest request) {
        if (request.getEventRequest() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(request.getEventRequest().getRequired())) {
            return true;
        }
        return (request.getEventRequest().getEventName() != null && !request.getEventRequest().getEventName().isBlank())
                || (request.getEventRequest().getPayloadFields() != null && !request.getEventRequest().getPayloadFields().isEmpty())
                || (request.getEventRequest().getConsumers() != null && !request.getEventRequest().getConsumers().isEmpty());
    }

    private LaneCompletionCommands.ApiContractResult asContractResult(final ApiLaneContractResult source) {
        return new LaneCompletionCommands.ApiContractResult(
                source.getScope(),
                source.getMethod() == null ? null : source.getMethod().getValue(),
                source.getPath(),
                source.getOperationId(),
                source.getNotes(),
                source.getArtifacts() == null ? List.of() : source.getArtifacts().stream().map(this::asGeneratedArtifact).toList()
        );
    }

    private LaneCompletionCommands.ApiGeneratedArtifact asGeneratedArtifact(final ApiLaneGeneratedArtifact source) {
        return new LaneCompletionCommands.ApiGeneratedArtifact(
                source.getDependency(),
                source.getRole() == null ? null : source.getRole().getValue(),
                source.getKind() == null ? null : source.getKind().getValue(),
                source.getRunId(),
                source.getNotes()
        );
    }
}
