package com.sitionix.forgeai.it.infra;

import com.app_afesox.fgaisox.api_first.dto.ApiLaneContractResult;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneGeneratedArtifact;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.agentexecutor.LaneCompletionContractResolver;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionContractResult;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionEvidence;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionGeneratedArtifact;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionCommands;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionOutput;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBeIntegrationFlow;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePersistenceChange;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeAffectedSurface;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadDataCheck;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadIntegrationFlow;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadIntegrationTestCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadUnitTestNote;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteLaneCompletion;
import com.sitionix.forgeai.mapper.ApiTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.ArchitectTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.EventTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.ImplementBeTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.ImplementFeTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.QaLeadCompletionTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.QaLeadTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.TestItTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.TestUiTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.TestUnitTicketPayloadApiMapper;
import com.sitionix.forgeai.mapper.UnitTestCompletionTicketPayloadApiMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class LaneCompletionTestFacade {

    private final CompleteLaneCompletion completion;
    private final CompletionRequestFixtureLoader fixtureLoader;
    private final LaneRepository laneRepository;
    private final TicketRepository ticketRepository;
    private final LaneCompletionContractResolver laneCompletionContractResolver;
    private final ObjectMapper objectMapper;
    private final ArchitectTicketPayloadApiMapper architectTicketPayloadApiMapper;
    private final QaLeadTicketPayloadApiMapper qaLeadTicketPayloadApiMapper;
    private final ImplementBeTicketPayloadApiMapper implementBeTicketPayloadApiMapper;
    private final ImplementFeTicketPayloadApiMapper implementFeTicketPayloadApiMapper;
    private final ApiTicketPayloadApiMapper apiTicketPayloadApiMapper;
    private final EventTicketPayloadApiMapper eventTicketPayloadApiMapper;
    private final TestUnitTicketPayloadApiMapper testUnitTicketPayloadApiMapper;
    private final TestItTicketPayloadApiMapper testItTicketPayloadApiMapper;
    private final TestUiTicketPayloadApiMapper testUiTicketPayloadApiMapper;
    private final QaLeadCompletionTicketPayloadApiMapper qaLeadCompletionTicketPayloadApiMapper;
    private final UnitTestCompletionTicketPayloadApiMapper unitTestCompletionTicketPayloadApiMapper;

    public LaneCompletionTestFacade(final CompleteLaneCompletion completion,
                                    final CompletionRequestFixtureLoader fixtureLoader,
                                    final LaneRepository laneRepository,
                                    final TicketRepository ticketRepository,
                                    final LaneCompletionContractResolver laneCompletionContractResolver,
                                    final ObjectMapper objectMapper,
                                    final ArchitectTicketPayloadApiMapper architectTicketPayloadApiMapper,
                                    final QaLeadTicketPayloadApiMapper qaLeadTicketPayloadApiMapper,
                                    final ImplementBeTicketPayloadApiMapper implementBeTicketPayloadApiMapper,
                                    final ImplementFeTicketPayloadApiMapper implementFeTicketPayloadApiMapper,
                                    final ApiTicketPayloadApiMapper apiTicketPayloadApiMapper,
                                    final EventTicketPayloadApiMapper eventTicketPayloadApiMapper,
                                    final TestUnitTicketPayloadApiMapper testUnitTicketPayloadApiMapper,
                                    final TestItTicketPayloadApiMapper testItTicketPayloadApiMapper,
                                    final TestUiTicketPayloadApiMapper testUiTicketPayloadApiMapper,
                                    final QaLeadCompletionTicketPayloadApiMapper qaLeadCompletionTicketPayloadApiMapper,
                                    final UnitTestCompletionTicketPayloadApiMapper unitTestCompletionTicketPayloadApiMapper) {
        this.completion = completion;
        this.fixtureLoader = fixtureLoader;
        this.laneRepository = laneRepository;
        this.ticketRepository = ticketRepository;
        this.laneCompletionContractResolver = laneCompletionContractResolver;
        this.objectMapper = objectMapper;
        this.architectTicketPayloadApiMapper = architectTicketPayloadApiMapper;
        this.qaLeadTicketPayloadApiMapper = qaLeadTicketPayloadApiMapper;
        this.implementBeTicketPayloadApiMapper = implementBeTicketPayloadApiMapper;
        this.implementFeTicketPayloadApiMapper = implementFeTicketPayloadApiMapper;
        this.apiTicketPayloadApiMapper = apiTicketPayloadApiMapper;
        this.eventTicketPayloadApiMapper = eventTicketPayloadApiMapper;
        this.testUnitTicketPayloadApiMapper = testUnitTicketPayloadApiMapper;
        this.testItTicketPayloadApiMapper = testItTicketPayloadApiMapper;
        this.testUiTicketPayloadApiMapper = testUiTicketPayloadApiMapper;
        this.qaLeadCompletionTicketPayloadApiMapper = qaLeadCompletionTicketPayloadApiMapper;
        this.unitTestCompletionTicketPayloadApiMapper = unitTestCompletionTicketPayloadApiMapper;
    }

    public void completeAnalyzerLane(final UUID ticketId, final UUID laneId) {
        this.completeAnalyzerLane(ticketId, laneId, request -> {
        });
    }

    public void completeAnalyzerLane(final UUID ticketId,
                                     final UUID laneId,
                                     final Consumer<CompleteAnalyzerLaneRequestDTO> mutator) {
        final CompleteAnalyzerLaneRequestDTO request = this.fixtureLoader.read("requestCompleteAnalyzerLane.json", CompleteAnalyzerLaneRequestDTO.class, mutator);
        final CompletionOverrides overrides = new CompletionOverrides()
                .scope(Agent.ARCHITECT, request.getArchitectHandoff() == null ? null : request.getArchitectHandoff().getScope())
                .scope(Agent.QA_LEAD, request.getQaLeadHandoff() == null ? null : request.getQaLeadHandoff().getScope())
                .payload(Agent.ARCHITECT, request.getArchitectHandoff() == null ? null : this.architectTicketPayloadApiMapper.asArchitectPayload(request.getArchitectHandoff()))
                .payload(Agent.QA_LEAD, request.getQaLeadHandoff() == null ? null : this.qaLeadTicketPayloadApiMapper.asQaLeadPayload(request.getQaLeadHandoff()));
        this.completeProducedOutputs(ticketId, laneId, overrides);
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
        final CompletionOverrides overrides = new CompletionOverrides()
                .scope(Agent.IMPLEMENT_BE, request.getImplementationHandoff() == null ? null : request.getImplementationHandoff().getScope())
                .scope(Agent.IMPLEMENT_FE, request.getImplementationHandoff() == null ? null : request.getImplementationHandoff().getScope())
                .scope(Agent.API, request.getApiRequest() == null ? null : request.getApiRequest().getScope())
                .scope(Agent.EVENT, request.getEventRequest() == null ? null : request.getEventRequest().getScope())
                .payload(Agent.IMPLEMENT_BE, request.getImplementationHandoff() == null ? null : this.implementBeTicketPayloadApiMapper.asImplementBePayload(request.getImplementationHandoff()))
                .payload(Agent.IMPLEMENT_FE, request.getImplementationHandoff() == null ? null : this.implementFeTicketPayloadApiMapper.asImplementFePayload(request.getImplementationHandoff()))
                .payload(Agent.API, request.getApiRequest() == null ? null : this.apiTicketPayloadApiMapper.asApiPayload(request.getApiRequest()))
                .payload(Agent.EVENT, request.getEventRequest() == null ? null : this.eventTicketPayloadApiMapper.asEventPayload(request.getEventRequest()))
                .required(Agent.API, this.shouldCreateApiTask(request))
                .required(Agent.EVENT, this.shouldCreateEventTask(request));
        this.completeProducedOutputs(ticketId, laneId, overrides);
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
        this.completeProducedOutputs(ticketId, laneId, new CompletionOverrides().apiEvidence(this.asApiEvidence(request)));
    }

    public void completeImplementBeLane(final UUID ticketId, final UUID laneId) {
        this.completeImplementBeLane(ticketId, laneId, request -> {
        });
    }

    public void completeImplementBeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final Consumer<CompleteImplementBeLaneRequestDTO> mutator) {
        final CompleteImplementBeLaneRequestDTO request = this.fixtureLoader.read("requestCompleteImplementBeLane.json", CompleteImplementBeLaneRequestDTO.class, mutator);
        this.completeProducedOutputs(ticketId, laneId, new CompletionOverrides()
                .allScopes(request.getScope())
                .payload(Agent.TEST_UNIT, this.testUnitTicketPayloadApiMapper.asTestUnitPayload(request))
                .payload(Agent.TEST_IT, this.testItTicketPayloadApiMapper.asTestItPayload(request)));
    }

    public void completeImplementFeLane(final UUID ticketId, final UUID laneId) {
        this.completeImplementFeLane(ticketId, laneId, request -> {
        });
    }

    public void completeImplementFeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final Consumer<CompleteImplementFeLaneRequestDTO> mutator) {
        final CompleteImplementFeLaneRequestDTO request = this.fixtureLoader.read("requestCompleteImplementFeLane.json", CompleteImplementFeLaneRequestDTO.class, mutator);
        this.completeProducedOutputs(ticketId, laneId, new CompletionOverrides()
                .allScopes(request.getScope())
                .payload(Agent.TEST_UI, this.testUiTicketPayloadApiMapper.asTestUiPayload(request)));
    }

    public void completeQaLeadLaneBackend(final UUID ticketId, final UUID laneId) {
        this.completeQaLeadLaneBackend(ticketId, laneId, request -> {
        });
    }

    public void completeQaLeadLaneBackend(final UUID ticketId,
                                          final UUID laneId,
                                          final Consumer<CompleteQaLeadLaneRequestDTO> mutator) {
        final CompleteQaLeadLaneRequestDTO request = this.fixtureLoader.read("requestCompleteQaLeadLaneBackend.json", CompleteQaLeadLaneRequestDTO.class, mutator);
        this.completeQaLeadLane(ticketId, laneId, request);
    }

    public void completeQaLeadLaneBackendNotRequired(final UUID ticketId, final UUID laneId) {
        final CompleteQaLeadLaneRequestDTO request = this.fixtureLoader.read("requestCompleteQaLeadLaneBackendNotRequired.json", CompleteQaLeadLaneRequestDTO.class);
        this.completeQaLeadLane(ticketId, laneId, request);
    }

    public void completeItTestLane(final UUID ticketId, final UUID laneId) {
        this.completeItTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeItTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final Consumer<CompleteItTestLaneRequestDTO> mutator) {
        final CompleteItTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteItTestLane.json", CompleteItTestLaneRequestDTO.class, mutator);
        this.complete(ticketId, laneId, new LaneCompletionPayload(List.of(), null, this.asMap(TestItCompletionPayload.builder()
                .scope(request.getScope())
                .summary(request.getSummary())
                .coveredCases(request.getCoveredCases())
                .build())));
    }

    public void completeUiTestLane(final UUID ticketId, final UUID laneId) {
        this.completeUiTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeUiTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final Consumer<CompleteUiTestLaneRequestDTO> mutator) {
        final CompleteUiTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteUiTestLane.json", CompleteUiTestLaneRequestDTO.class, mutator);
        this.completeProducedOutputs(ticketId, laneId, new CompletionOverrides().allScopes(request.getScope()));
    }

    public void completeUnitTestLane(final UUID ticketId, final UUID laneId) {
        this.completeUnitTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeUnitTestLane(final UUID ticketId,
                                     final UUID laneId,
                                     final Consumer<CompleteUnitTestLaneRequestDTO> mutator) {
        final CompleteUnitTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteUnitTestLane.json", CompleteUnitTestLaneRequestDTO.class, mutator);
        this.completeProducedOutputs(ticketId, laneId, new CompletionOverrides()
                .sourceScope(request.getScope())
                .payload(Agent.REVIEWER, this.unitTestCompletionTicketPayloadApiMapper.asReviewerPayload(request)));
    }

    public void completeReviewerLane(final UUID ticketId, final UUID laneId) {
        this.complete(ticketId, laneId, new LaneCompletionPayload(List.of(), null, Map.of()));
    }

    public void completeEventLane(final UUID ticketId, final UUID laneId) {
        this.complete(ticketId, laneId, new LaneCompletionPayload(List.of(), null, Map.of()));
    }

    private void completeQaLeadLane(final UUID ticketId,
                                    final UUID laneId,
                                    final CompleteQaLeadLaneRequestDTO request) {
        final CompletionOverrides overrides = new CompletionOverrides()
                .allScopes(request.getScope())
                .payload(Agent.TEST_UNIT, this.qaLeadCompletionTicketPayloadApiMapper.asTestUnitPayload(request))
                .payload(Agent.TEST_IT, this.qaLeadCompletionTicketPayloadApiMapper.asTestItPayload(request))
                .payload(Agent.TEST_UI, this.qaLeadCompletionTicketPayloadApiMapper.asTestUiPayload(request))
                .required(Agent.TEST_UNIT, Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired()))
                .required(Agent.TEST_IT, Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired()))
                .required(Agent.TEST_UI, Boolean.TRUE.equals(request.getTestLaneRequirements().getUiTestRequired()));
        this.completeProducedOutputs(ticketId, laneId, overrides);
    }

    private void completeProducedOutputs(final UUID ticketId,
                                         final UUID laneId,
                                         final CompletionOverrides overrides) {
        final Lane sourceLane = this.sourceLaneForCompletion(laneId);
        final List<LaneCompletionOutput> outputs = this.laneRepository.findCompletionTargetLanes(laneId).stream()
                .filter(targetLane -> overrides.shouldInclude(sourceLane, targetLane))
                .map(targetLane -> this.output(sourceLane, targetLane, overrides))
                .toList();
        this.complete(ticketId, laneId, new LaneCompletionPayload(outputs, overrides.apiEvidence(), Map.of()));
    }

    private LaneCompletionOutput output(final Lane sourceLane,
                                        final Lane targetLane,
                                        final CompletionOverrides overrides) {
        final String outputScope = overrides.scope(sourceLane, targetLane);
        final Boolean required = overrides.required(targetLane);
        final Map<String, Object> payload = Boolean.FALSE.equals(required)
                ? null
                : this.targetPayload(sourceLane, targetLane, outputScope, overrides);
        return new LaneCompletionOutput(targetLane.getAgent().getId(), outputScope, required, payload);
    }

    private Lane sourceLaneForCompletion(final UUID laneId) {
        this.ticketRepository.moveLaneToInProgressIfReady(laneId);
        return this.ticketRepository.findByLaneId(laneId).orElseThrow();
    }

    private Map<String, Object> targetPayload(final Lane sourceLane,
                                              final Lane targetLane,
                                              final String outputScope,
                                              final CompletionOverrides overrides) {
        final Class<? extends AgentTicketPayload> payloadType =
                this.laneCompletionContractResolver.inputPayloadType(sourceLane.getAgent(), targetLane.getAgent());
        if (Objects.equals(sourceLane.getAgent(), Agent.API)
                && (Objects.equals(payloadType, ImplementBePayload.class) || Objects.equals(payloadType, ImplementFePayload.class))) {
            return this.apiImplementationPayload(targetLane, outputScope, overrides.apiEvidence());
        }
        final Object overriddenPayload = overrides.payload(targetLane);
        if (overriddenPayload != null) {
            return this.asMap(overriddenPayload);
        }
        return this.samplePayload(payloadType, outputScope);
    }

    private void complete(final UUID ticketId,
                          final UUID laneId,
                          final LaneCompletionPayload payload) {
        this.ticketRepository.moveLaneToInProgressIfReady(laneId);
        this.completion.completeLane(new LaneCompletionCommands.CompleteLane(
                ticketId,
                laneId,
                this.objectMapper.convertValue(payload, new TypeReference<>() {
                })
        ));
    }

    private Map<String, Object> samplePayload(final Class<? extends AgentTicketPayload> payloadType,
                                              final String scope) {
        if (Objects.equals(payloadType, ArchitectPayload.class)) {
            return this.asMap(ArchitectPayload.builder()
                    .requirements(this.set("Requirement for " + scope))
                    .constraints(this.set("Constraint for " + scope))
                    .nonGoals(Set.of())
                    .risks(Set.of())
                    .dependencies(Set.of())
                    .build());
        }
        if (Objects.equals(payloadType, QaLeadPayload.class)) {
            return this.asMap(QaLeadPayload.builder()
                    .requirements(this.set("Requirement for " + scope))
                    .constraints(this.set("Constraint for " + scope))
                    .nonGoals(Set.of())
                    .risks(Set.of())
                    .dependencies(Set.of())
                    .qualityFocus(this.set("Quality focus for " + scope))
                    .edgeConsiderations(this.set("Edge consideration for " + scope))
                    .build());
        }
        if (Objects.equals(payloadType, ApiPayload.class)) {
            return this.asMap(ApiPayload.builder()
                    .required(true)
                    .reason("Required for " + scope)
                    .scope(scope)
                    .summary("API contract for " + scope)
                    .operations(List.of())
                    .consumers(Set.of())
                    .notes(Set.of())
                    .build());
        }
        if (Objects.equals(payloadType, EventPayload.class)) {
            return this.asMap(EventPayload.builder()
                    .required(true)
                    .reason("Required for " + scope)
                    .scope(scope)
                    .summary("Event contract for " + scope)
                    .eventName("event for " + scope)
                    .payloadFields(List.of())
                    .consumers(Set.of())
                    .notes(Set.of())
                    .build());
        }
        if (Objects.equals(payloadType, ImplementBePayload.class)) {
            return this.asMap(ImplementBePayload.builder()
                    .task("Implement backend changes for " + scope)
                    .scope(scope)
                    .summary("Backend implementation for " + scope)
                    .requirements(this.set("Requirement for " + scope))
                    .constraints(Set.of())
                    .nonGoals(Set.of())
                    .architectureDecision("Follow existing backend architecture.")
                    .dependencies(Set.of())
                    .acceptanceNotes(Set.of())
                    .risks(Set.of())
                    .build());
        }
        if (Objects.equals(payloadType, ImplementFePayload.class)) {
            return this.asMap(ImplementFePayload.builder()
                    .task("Implement frontend changes for " + scope)
                    .scope(scope)
                    .summary("Frontend implementation for " + scope)
                    .requirements(this.set("Requirement for " + scope))
                    .constraints(Set.of())
                    .nonGoals(Set.of())
                    .architectureDecision("Follow existing frontend architecture.")
                    .dependencies(Set.of())
                    .acceptanceNotes(Set.of())
                    .risks(Set.of())
                    .build());
        }
        if (Objects.equals(payloadType, TestUnitPayload.class)) {
            return this.asMap(new TestUnitPayload(
                    "Run unit tests for " + scope,
                    scope,
                    "Unit test coverage for " + scope,
                    this.set(new ImplementBeChangedFile("src/main/java/Example.java", "changed")),
                    new UnitTestSonar(80.0D, 0),
                    this.set(new QaLeadUnitTestNote("unit", "cover happy path"))
            ));
        }
        if (Objects.equals(payloadType, TestItPayload.class)) {
            return this.asMap(new TestItPayload(
                    "Run integration tests for " + scope,
                    scope,
                    "Integration test coverage for " + scope,
                    this.set(new ImplementBeIntegrationFlow("flow", "POST", "/api/test", "testOperation", "summary")),
                    this.set(new ImplementBePersistenceChange("field", "description", "persist description")),
                    new UnitTestSonar(80.0D, 0),
                    this.set(this.qaLeadIntegrationTestCase()),
                    this.set(new QaLeadUnitTestNote("integration", "cover contract"))
            ));
        }
        if (Objects.equals(payloadType, TestUiPayload.class)) {
            return this.asMap(new TestUiPayload(
                    "Run UI tests for " + scope,
                    scope,
                    "UI test coverage for " + scope,
                    this.set(new ImplementFeChangedFile("src/App.tsx", "changed")),
                    this.set(new ImplementFeAffectedSurface("page", "Project", "description field")),
                    this.set("Description is visible"),
                    new UnitTestSonar(80.0D, 0),
                    this.set(new QaLeadUnitTestNote("ui", "cover rendering"))
            ));
        }
        if (Objects.equals(payloadType, QaLeadTestUnitPayload.class)) {
            return this.asMap(new QaLeadTestUnitPayload(
                    "Plan unit tests for " + scope,
                    scope,
                    "Unit test plan for " + scope,
                    this.set(new QaLeadUnitTestNote("unit", "cover mapper"))
            ));
        }
        if (Objects.equals(payloadType, QaLeadTestItPayload.class)) {
            return this.asMap(new QaLeadTestItPayload(
                    "Plan integration tests for " + scope,
                    scope,
                    "Integration test plan for " + scope,
                    this.set(this.qaLeadIntegrationTestCase()),
                    this.set(new QaLeadUnitTestNote("integration", "cover persistence"))
            ));
        }
        if (Objects.equals(payloadType, QaLeadTestUiPayload.class)) {
            return this.asMap(new QaLeadTestUiPayload(
                    "Plan UI tests for " + scope,
                    scope,
                    "UI test plan for " + scope,
                    this.set(new QaLeadUnitTestNote("ui", "cover field visibility"))
            ));
        }
        if (Objects.equals(payloadType, ReviewerPayload.class)) {
            return this.asMap(new ReviewerPayload(
                    "Review changes for " + scope,
                    scope,
                    "Review payload for " + scope,
                    List.of("src/main/java/Example.java"),
                    new UnitTestSonar(80.0D, 0)
            ));
        }
        if (Objects.equals(payloadType, TestItCompletionPayload.class)) {
            return this.asMap(TestItCompletionPayload.builder()
                    .scope(scope)
                    .summary("Integration tests passed for " + scope)
                    .coveredCases(List.of("happy path"))
                    .build());
        }
        return this.reflectivePayload(payloadType, scope);
    }

    private Map<String, Object> reflectivePayload(final Class<? extends AgentTicketPayload> payloadType,
                                                  final String scope) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        for (final Field field : payloadType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            payload.put(field.getName(), this.sampleValue(field, scope));
        }
        return payload;
    }

    private QaLeadIntegrationTestCase qaLeadIntegrationTestCase() {
        return new QaLeadIntegrationTestCase(
                "happy path",
                new QaLeadIntegrationFlow("flow", "POST", "/api/test", "testOperation"),
                this.set("request exists"),
                this.set("request is submitted"),
                this.set("response is persisted"),
                this.set(new QaLeadDataCheck("description", "is saved")),
                "HIGH"
        );
    }

    private Map<String, Object> apiImplementationPayload(final Lane targetLane,
                                                         final String scope,
                                                         final ApiCompletionEvidence evidence) {
        final List<ApiCompletionContractResult> contracts = this.apiContractsForTarget(targetLane, evidence);
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "Implement API contract integration for " + scope);
        payload.put("scope", scope);
        payload.put("summary", evidence == null ? null : evidence.summary());
        payload.put("requirements", this.contractRequirements(contracts));
        payload.put("constraints", this.contractNotes(contracts));
        payload.put("nonGoals", Set.of());
        payload.put("architectureDecision", "Use generated API artifacts directly.");
        payload.put("dependencies", this.contractDependencies(targetLane, contracts));
        payload.put("acceptanceNotes", this.contractAcceptanceNotes(targetLane, contracts));
        payload.put("risks", Set.of());
        return payload;
    }

    private Set<String> contractRequirements(final List<ApiCompletionContractResult> contracts) {
        return contracts.stream()
                .map(contract -> contract.method() + " " + contract.path() + " (" + contract.operationId() + ")")
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractNotes(final List<ApiCompletionContractResult> contracts) {
        return contracts.stream()
                .filter(contract -> contract.notes() != null)
                .flatMap(contract -> contract.notes().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractDependencies(final Lane targetLane,
                                             final List<ApiCompletionContractResult> contracts) {
        return contracts.stream()
                .filter(contract -> contract.artifacts() != null)
                .flatMap(contract -> contract.artifacts().stream())
                .filter(artifact -> this.artifactTargetsLane(targetLane, artifact))
                .map(ApiCompletionGeneratedArtifact::dependency)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractAcceptanceNotes(final Lane targetLane,
                                                final List<ApiCompletionContractResult> contracts) {
        return contracts.stream()
                .filter(contract -> contract.artifacts() != null)
                .flatMap(contract -> contract.artifacts().stream())
                .filter(artifact -> this.artifactTargetsLane(targetLane, artifact))
                .filter(artifact -> artifact.notes() != null)
                .flatMap(artifact -> artifact.notes().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private List<ApiCompletionContractResult> apiContractsForTarget(final Lane targetLane,
                                                                    final ApiCompletionEvidence evidence) {
        if (evidence == null || evidence.contracts() == null) {
            return List.of();
        }
        return evidence.contracts().stream()
                .filter(contract -> this.contractTargetsLane(targetLane, contract))
                .toList();
    }

    private boolean contractTargetsLane(final Lane targetLane,
                                        final ApiCompletionContractResult contract) {
        if (Objects.equals(targetLane.getAgent(), Agent.IMPLEMENT_BE)) {
            return Objects.equals(contract.scope(), targetLane.getScope());
        }
        if (Objects.equals(targetLane.getAgent(), Agent.IMPLEMENT_FE)) {
            return contract.artifacts() != null && contract.artifacts().stream().anyMatch(this::isFrontendArtifact);
        }
        return false;
    }

    private boolean artifactTargetsLane(final Lane targetLane,
                                        final ApiCompletionGeneratedArtifact artifact) {
        final boolean frontendArtifact = this.isFrontendArtifact(artifact);
        return Objects.equals(targetLane.getAgent(), Agent.IMPLEMENT_FE) == frontendArtifact;
    }

    private boolean isFrontendArtifact(final ApiCompletionGeneratedArtifact artifact) {
        return Objects.equals(artifact.kind(), "NPM")
                || Objects.equals(artifact.role(), "FRONTEND_CONTRACT");
    }

    private <T> Set<T> set(final T value) {
        final LinkedHashSet<T> values = new LinkedHashSet<>();
        values.add(value);
        return values;
    }

    private Map<String, Object> asMap(final Object value) {
        final Map<String, Object> result = this.objectMapper.convertValue(value, new TypeReference<>() {
        });
        return this.withoutEmptyGeneratedDefaults(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withoutEmptyGeneratedDefaults(final Map<String, Object> source) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : source.entrySet()) {
            final Object value = this.withoutEmptyGeneratedDefaults(entry.getValue());
            if (this.isGeneratedDefault(entry.getKey(), value)) {
                continue;
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object withoutEmptyGeneratedDefaults(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return this.withoutEmptyGeneratedDefaults((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::withoutEmptyGeneratedDefaults)
                    .toList();
        }
        return value;
    }

    private boolean isGeneratedDefault(final String key, final Object value) {
        return Objects.equals(key, "parameters") && value instanceof List<?> list && list.isEmpty();
    }

    private Object sampleValue(final Field field, final String scope) {
        final Class<?> type = field.getType();
        if (Objects.equals(field.getName(), "scope")) {
            return scope;
        }
        if (Objects.equals(type, String.class)) {
            return field.getName() + " for " + scope;
        }
        if (Objects.equals(type, Boolean.class) || Objects.equals(type, boolean.class)) {
            return true;
        }
        if (Objects.equals(type, Long.class) || Objects.equals(type, long.class)) {
            return 1L;
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (Set.class.isAssignableFrom(type)) {
            return Set.of("sample");
        }
        return null;
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

    private ApiCompletionEvidence asApiEvidence(final CompleteApiLaneRequest source) {
        return new ApiCompletionEvidence(
                source.getSummary(),
                source.getPrUrl(),
                source.getRepo(),
                source.getContracts() == null ? List.of() : source.getContracts().stream().map(this::asContractResult).toList()
        );
    }

    private ApiCompletionContractResult asContractResult(final ApiLaneContractResult source) {
        return new ApiCompletionContractResult(
                source.getScope(),
                source.getMethod() == null ? null : source.getMethod().getValue(),
                source.getPath(),
                source.getOperationId(),
                source.getNotes(),
                source.getArtifacts() == null ? List.of() : source.getArtifacts().stream().map(this::asGeneratedArtifact).toList()
        );
    }

    private ApiCompletionGeneratedArtifact asGeneratedArtifact(final ApiLaneGeneratedArtifact source) {
        return new ApiCompletionGeneratedArtifact(
                source.getDependency(),
                source.getRole() == null ? null : source.getRole().getValue(),
                source.getKind() == null ? null : source.getKind().getValue(),
                source.getRunId(),
                source.getNotes()
        );
    }

    private final class CompletionOverrides {
        private final Map<Agent, String> scopes = new EnumMap<>(Agent.class);
        private final Map<Agent, Boolean> required = new EnumMap<>(Agent.class);
        private final Map<Agent, Object> payloads = new EnumMap<>(Agent.class);
        private ApiCompletionEvidence apiEvidence;
        private String allScopes;
        private String sourceScope;

        CompletionOverrides scope(final Agent agent, final String scope) {
            if (scope != null) {
                this.scopes.put(agent, scope);
            }
            return this;
        }

        CompletionOverrides allScopes(final String scope) {
            this.allScopes = scope;
            return this;
        }

        CompletionOverrides sourceScope(final String scope) {
            this.sourceScope = scope;
            return this;
        }

        CompletionOverrides required(final Agent agent, final boolean value) {
            this.required.put(agent, value);
            return this;
        }

        CompletionOverrides payload(final Agent agent, final Object payload) {
            if (payload != null) {
                this.payloads.put(agent, payload);
            }
            return this;
        }

        CompletionOverrides apiEvidence(final ApiCompletionEvidence value) {
            this.apiEvidence = value;
            return this;
        }

        ApiCompletionEvidence apiEvidence() {
            return this.apiEvidence;
        }

        String scope(final Lane sourceLane, final Lane targetLane) {
            if (this.sourceScope != null && !Objects.equals(this.sourceScope, sourceLane.getScope())) {
                return this.sourceScope;
            }
            return this.scopes.getOrDefault(targetLane.getAgent(), this.allScopes == null ? targetLane.getScope() : this.allScopes);
        }

        Object payload(final Lane targetLane) {
            return this.payloads.get(targetLane.getAgent());
        }

        Boolean required(final Lane targetLane) {
            return this.required.get(targetLane.getAgent());
        }

        boolean shouldInclude(final Lane sourceLane, final Lane targetLane) {
            if (!Objects.equals(sourceLane.getAgent(), Agent.API)) {
                return true;
            }
            return !LaneCompletionTestFacade.this.apiContractsForTarget(targetLane, this.apiEvidence).isEmpty();
        }
    }
}
