package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CompleteReviewerTask;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneCompletionDispatcher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CompleteAgentTasks completeAgentTasks;
    private final CreateAgentTask createAgentTask;
    private final CompleteAgentLane completeAgentLane;
    private final CompleteReviewerTask completeReviewerTask;
    private final ValidateApiLaneEvidence validateApiLaneEvidence;
    private final TicketRepository ticketRepository;
    private final LaneRepository laneRepository;
    private final AgentTicketRepository agentTicketRepository;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final ObjectMapper objectMapper;

    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> evidence) {
        final Map<String, Object> completionPayload = this.requireCompletionPayload(evidence);
        switch (lane.getAgent()) {
            case ANALYZER -> this.validateAnalyzerPayload(lane, completionPayload);
            case ARCHITECT -> this.validateArchitectPayload(lane, completionPayload);
            case API -> this.validateApiPayload(lane, completionPayload);
            case QA_LEAD -> this.validateQaLeadPayload(lane, completionPayload);
            case IMPLEMENT_BE, IMPLEMENT_FE, TEST_UI, TEST_UNIT, EVENT, REVIEWER -> this.validateScopePayload(lane, completionPayload);
            case TEST_IT -> this.validateScopePayload(lane, completionPayload);
        }
    }

    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> evidence) {
        final Map<String, Object> completionPayload = this.requireCompletionPayload(evidence);
        switch (lane.getAgent()) {
            case ANALYZER -> this.completeAnalyzer(lane, completionPayload);
            case ARCHITECT -> this.completeArchitect(lane, completionPayload);
            case API -> this.completeApi(lane, completionPayload);
            case QA_LEAD -> this.completeQaLead(lane, completionPayload);
            case IMPLEMENT_BE -> this.completeImplementBe(lane, completionPayload);
            case IMPLEMENT_FE -> this.completeImplementFe(lane, completionPayload);
            case TEST_UNIT -> this.completeTestUnit(lane, completionPayload);
            case TEST_IT -> this.completeTestIt(lane, completionPayload);
            case TEST_UI -> this.completeAgentTasks.complete(lane.getLaneId(), List.of());
            case REVIEWER -> this.completeReviewerTask.complete(lane.getTicketId());
            case EVENT -> this.completeAgentLane.completeAndPrepareAgents(lane.getLaneId());
        }
    }

    private void completeAnalyzer(final ReadyToStartLane lane, final Map<String, Object> payload) {
        final AgentTicket<ArchitectPayload> architectTicket = this.ticket(
                lane,
                this.convert(this.requireMap(payload, "architectHandoff"), ArchitectPayload.class),
                lane.getScope(),
                Agent.ARCHITECT
        );
        final AgentTicket<QaLeadPayload> qaLeadTicket = this.ticket(
                lane,
                this.convert(this.requireMap(payload, "qaLeadHandoff"), QaLeadPayload.class),
                lane.getScope(),
                Agent.QA_LEAD
        );
        this.completeAgentTasks.complete(lane.getLaneId(), List.of(architectTicket, qaLeadTicket));
    }

    private void completeArchitect(final ReadyToStartLane lane, final Map<String, Object> payload) {
        final String implementationScope = this.requireText(payload, "implementationScope");
        this.requireExpectedScope(lane, implementationScope);
        final Agent implementationAgent = this.resolveImplementationAgent(implementationScope);
        if (Agent.IMPLEMENT_BE.equals(implementationAgent)) {
            final AgentTicket<ImplementBePayload> ticket = this.ticket(
                    lane,
                    this.convert(this.requireMap(payload, "implementationHandoff"), ImplementBePayload.class),
                    implementationScope,
                    Agent.IMPLEMENT_BE
            );
            this.completeAgentTasks.complete(lane.getLaneId(), List.of(ticket));
        } else {
            final AgentTicket<ImplementFePayload> ticket = this.ticket(
                    lane,
                    this.convert(this.requireMap(payload, "implementationHandoff"), ImplementFePayload.class),
                    implementationScope,
                    Agent.IMPLEMENT_FE
            );
            this.completeAgentTasks.complete(lane.getLaneId(), List.of(ticket));
        }

        this.createOptionalArchitectLaneTask(lane, payload, "apiRequired", Agent.API, ScopeModeHolder.GLOBAL_SCOPE, "apiRequest", ApiPayload.class);
        this.createOptionalArchitectLaneTask(lane, payload, "eventRequired", Agent.EVENT, ScopeModeHolder.GLOBAL_SCOPE, "eventRequest", EventPayload.class);
    }

    private void completeApi(final ReadyToStartLane lane, final Map<String, Object> payload) {
        final List<Map<String, Object>> contracts = this.requireListOfMaps(payload, "contracts");
        final String summary = this.requireText(payload, "summary");
        final Set<String> callbackScopes = contracts.stream()
                .map(contract -> Objects.toString(contract.get("scope"), null))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.validateApiLaneEvidence.validate(
                lane.getLaneId(),
                callbackScopes,
                ApiLaneEvidencePayload.builder()
                        .prUrl(Objects.toString(payload.get("prUrl"), null))
                        .repo(Objects.toString(payload.get("repo"), null))
                        .dependencies(this.apiEvidenceDependencies(contracts))
                        .build()
        );
        final ExecutionContext context = this.buildApiExecutionContext(contracts);
        this.findProducedImplementationLanes(lane.getLaneId())
                .forEach(targetLane -> this.createApiImplementationTask(lane, summary, targetLane, context));
    }

    private void completeQaLead(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, this.requireText(payload, "scope"));
        this.routeQaTestLane(lane, payload, "unitTestRequired", Agent.TEST_UNIT, "testUnitPayload", QaLeadTestUnitPayload.class);
        this.routeQaTestLane(lane, payload, "integrationTestRequired", Agent.TEST_IT, "testItPayload", QaLeadTestItPayload.class);
        this.routeQaTestLane(lane, payload, "uiTestRequired", Agent.TEST_UI, "testUiPayload", QaLeadTestUiPayload.class);
    }

    private void completeImplementBe(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, this.requireText(payload, "scope"));
        final AgentTicket<TestUnitPayload> testUnitTicket = this.ticket(lane, this.convert(payload, TestUnitPayload.class), lane.getScope(), Agent.TEST_UNIT);
        final AgentTicket<TestItPayload> testItTicket = this.ticket(lane, this.convert(payload, TestItPayload.class), lane.getScope(), Agent.TEST_IT);
        this.completeAgentTasks.complete(lane.getLaneId(), List.of(testUnitTicket, testItTicket));
    }

    private void completeImplementFe(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, this.requireText(payload, "scope"));
        final AgentTicket<TestUiPayload> testUiTicket = this.ticket(lane, this.convert(payload, TestUiPayload.class), lane.getScope(), Agent.TEST_UI);
        this.completeAgentTasks.complete(lane.getLaneId(), List.of(testUiTicket));
    }

    private void completeTestUnit(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, this.requireText(payload, "scope"));
        final AgentTicket<ReviewerPayload> reviewerTicket = this.ticket(lane, this.convert(payload, ReviewerPayload.class), ScopeModeHolder.GLOBAL_SCOPE, Agent.REVIEWER);
        this.completeAgentTasks.complete(lane.getLaneId(), List.of(reviewerTicket));
    }

    private void completeTestIt(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, this.requireText(payload, "scope"));
        final TestItCompletionPayload completionPayload = this.convert(payload, TestItCompletionPayload.class);
        final AgentTicket<TestItCompletionPayload> completionReport = AgentTicket.<TestItCompletionPayload>builder()
                .id(UUID.randomUUID())
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .status(AgentTicketStatus.CONSUMED)
                .scope(lane.getScope())
                .agent(Agent.TEST_IT)
                .payload(completionPayload)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        this.agentTicketRepository.save(completionReport);
        this.completeAgentLane.completeAndPrepareAgents(lane.getLaneId());
    }

    private void validateAnalyzerPayload(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, Objects.toString(this.requireMap(payload, "architectHandoff").get("scope"), lane.getScope()));
        this.requireExpectedScope(lane, Objects.toString(this.requireMap(payload, "qaLeadHandoff").get("scope"), lane.getScope()));
    }

    private void validateArchitectPayload(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, this.requireText(payload, "implementationScope"));
        if (this.requireBoolean(payload, "apiRequired")) {
            this.requireMap(payload, "apiRequest");
        }
        if (this.requireBoolean(payload, "eventRequired")) {
            this.requireMap(payload, "eventRequest");
        }
    }

    private void validateApiPayload(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireListOfMaps(payload, "contracts");
        this.requireText(payload, "summary");
        this.requireText(payload, "prUrl");
        this.requireText(payload, "repo");
    }

    private void validateQaLeadPayload(final ReadyToStartLane lane, final Map<String, Object> payload) {
        this.requireExpectedScope(lane, this.requireText(payload, "scope"));
        if (this.requireBoolean(payload, "unitTestRequired")) {
            this.requireMap(payload, "testUnitPayload");
        }
        if (this.requireBoolean(payload, "integrationTestRequired")) {
            this.requireMap(payload, "testItPayload");
        }
        if (this.requireBoolean(payload, "uiTestRequired")) {
            this.requireMap(payload, "testUiPayload");
        }
    }

    private void validateScopePayload(final ReadyToStartLane lane, final Map<String, Object> payload) {
        final Object scope = payload.get("scope");
        if (scope != null) {
            this.requireExpectedScope(lane, Objects.toString(scope, null));
        }
    }

    private <P extends AgentTicketPayload> void createOptionalArchitectLaneTask(final ReadyToStartLane lane,
                                                                                final Map<String, Object> payload,
                                                                                final String requiredField,
                                                                                final Agent targetAgent,
                                                                                final String scope,
                                                                                final String payloadField,
                                                                                final Class<P> payloadType) {
        if (this.requireBoolean(payload, requiredField)) {
            final AgentTicket<P> ticket = this.ticket(
                    lane,
                    this.convert(this.requireMap(payload, payloadField), payloadType),
                    scope,
                    targetAgent
            );
            this.completeAgentTasks.complete(lane.getLaneId(), List.of(ticket));
            return;
        }
        this.createAgentTask.markAsNotNeeded(lane.getLaneId(), scope, targetAgent);
    }

    private <P extends AgentTicketPayload> void routeQaTestLane(final ReadyToStartLane lane,
                                                                final Map<String, Object> payload,
                                                                final String requiredField,
                                                                final Agent targetAgent,
                                                                final String payloadField,
                                                                final Class<P> payloadType) {
        final boolean required = this.requireBoolean(payload, requiredField);
        if (this.laneRepository.findLaneToProduceOptional(lane.getLaneId(), lane.getScope(), targetAgent).isEmpty()) {
            return;
        }
        if (required) {
            this.createAgentTask.create(
                    this.ticket(lane, this.convert(this.requireMap(payload, payloadField), payloadType), lane.getScope(), targetAgent),
                    lane.getLaneId()
            );
            return;
        }
        this.createAgentTask.markAsNotNeeded(lane.getLaneId(), lane.getScope(), targetAgent);
    }

    private ExecutionContext buildApiExecutionContext(final List<Map<String, Object>> contracts) {
        final Map<String, String> apiFamilyByScope = this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> value.getPath() != null)
                .filter(value -> value.getContractRefs() != null && value.getContractRefs().get("api") != null)
                .collect(Collectors.toMap(
                        ServicePropertiesProvider.ServiceConfigView::getPath,
                        value -> value.getContractRefs().get("api").getApiFamily(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        final Map<String, List<Map<String, Object>>> contractsByScope = contracts.stream()
                .collect(Collectors.groupingBy(contract -> Objects.toString(contract.get("scope")), LinkedHashMap::new, Collectors.toList()));
        final Map<String, List<Map<String, Object>>> contractsByApiFamily = contracts.stream()
                .collect(Collectors.groupingBy(contract -> this.apiFamilyByScope(Objects.toString(contract.get("scope")), apiFamilyByScope), LinkedHashMap::new, Collectors.toList()));
        return new ExecutionContext(apiFamilyByScope, contractsByScope, contractsByApiFamily);
    }

    private void createApiImplementationTask(final ReadyToStartLane lane,
                                             final String summary,
                                             final Lane targetLane,
                                             final ExecutionContext context) {
        if (Objects.equals(targetLane.getAgent(), Agent.IMPLEMENT_BE)) {
            final List<Map<String, Object>> contracts = context.contractsByScope().getOrDefault(targetLane.getScope(), List.of());
            if (contracts.isEmpty()) {
                return;
            }
            this.completeAgentTasks.complete(lane.getLaneId(), List.of(this.apiImplementTicket(lane.getTicketId(), targetLane.getScope(), summary, contracts, Agent.IMPLEMENT_BE, false)));
            return;
        }
        final String frontendApiFamily = this.apiFamilyByScope(targetLane.getScope(), context.apiFamilyByScope());
        final List<Map<String, Object>> contracts = context.contractsByApiFamily().getOrDefault(frontendApiFamily, List.of());
        if (contracts.isEmpty()) {
            return;
        }
        this.completeAgentTasks.complete(lane.getLaneId(), List.of(this.apiImplementTicket(lane.getTicketId(), targetLane.getScope(), summary, contracts, Agent.IMPLEMENT_FE, true)));
    }

    private AgentTicket<?> apiImplementTicket(final UUID ticketId,
                                              final String scope,
                                              final String summary,
                                              final List<Map<String, Object>> contracts,
                                              final Agent agent,
                                              final boolean frontendOnly) {
        final Set<String> requirements = contracts.stream()
                .map(contract -> "%s %s (%s)".formatted(
                        Objects.toString(contract.get("method"), ""),
                        Objects.toString(contract.get("path"), ""),
                        Objects.toString(contract.get("operationId"), "")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> constraints = contracts.stream()
                .map(contract -> contract.get("notes"))
                .filter(Objects::nonNull)
                .flatMap(notes -> ((List<?>) notes).stream())
                .map(String::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> dependencies = new LinkedHashSet<>();
        final Set<String> acceptanceNotes = new LinkedHashSet<>();
        for (final Map<String, Object> contract : contracts) {
            for (final Map<String, Object> artifact : this.listOfMaps(contract.get("artifacts"))) {
                if (frontendOnly != this.isFrontendArtifact(artifact)) {
                    continue;
                }
                final String dependency = Objects.toString(artifact.get("dependency"), null);
                if (dependency != null && !dependency.isBlank()) {
                    dependencies.add(dependency);
                }
                for (final Object note : this.listOfObjects(artifact.get("notes"))) {
                    acceptanceNotes.add(String.valueOf(note));
                }
            }
        }
        if (Objects.equals(agent, Agent.IMPLEMENT_BE)) {
            return AgentTicket.<ImplementBePayload>builder()
                    .id(UUID.randomUUID())
                    .ticketId(ticketId)
                    .status(AgentTicketStatus.CREATED)
                    .scope(scope)
                    .agent(agent)
                    .payload(ImplementBePayload.builder()
                            .task("Implement API contract integration for " + scope)
                            .scope(scope)
                            .summary(summary)
                            .requirements(requirements)
                            .constraints(constraints)
                            .nonGoals(Set.of())
                            .architectureDecision("Use generated API artifacts directly.")
                            .dependencies(dependencies)
                            .acceptanceNotes(acceptanceNotes)
                            .risks(Set.of())
                            .build())
                    .build();
        }
        return AgentTicket.<ImplementFePayload>builder()
                .id(UUID.randomUUID())
                .ticketId(ticketId)
                .status(AgentTicketStatus.CREATED)
                .scope(scope)
                .agent(agent)
                .payload(ImplementFePayload.builder()
                        .task("Implement API contract integration for " + scope)
                        .scope(scope)
                        .summary(summary)
                        .requirements(requirements)
                        .constraints(constraints)
                        .nonGoals(Set.of())
                        .architectureDecision("Use generated API artifacts directly.")
                        .dependencies(dependencies)
                        .acceptanceNotes(acceptanceNotes)
                        .risks(Set.of())
                        .build())
                .build();
    }

    private List<Lane> findProducedImplementationLanes(final UUID laneId) {
        return this.laneRepository.findProducedLanes(laneId).stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.IMPLEMENT_BE) || Objects.equals(value.getAgent(), Agent.IMPLEMENT_FE))
                .toList();
    }

    private List<ApiLaneEvidenceDependency> apiEvidenceDependencies(final List<Map<String, Object>> contracts) {
        final List<ApiLaneEvidenceDependency> dependencies = new ArrayList<>();
        for (final Map<String, Object> contract : contracts) {
            final String scope = Objects.toString(contract.get("scope"), null);
            for (final Map<String, Object> artifact : this.listOfMaps(contract.get("artifacts"))) {
                final Object runId = artifact.get("runId");
                dependencies.add(new ApiLaneEvidenceDependency(
                        scope,
                        Objects.toString(artifact.get("role"), null),
                        runId == null ? null : Long.valueOf(String.valueOf(runId))
                ));
            }
        }
        return dependencies;
    }

    private boolean isFrontendArtifact(final Map<String, Object> artifact) {
        final String kind = Objects.toString(artifact.get("kind"), "");
        final String role = Objects.toString(artifact.get("role"), "");
        return "NPM".equalsIgnoreCase(kind) || "FRONTEND_CONTRACT".equalsIgnoreCase(role);
    }

    private String apiFamilyByScope(final String scope, final Map<String, String> apiFamilyByScope) {
        final String apiFamily = apiFamilyByScope.get(scope);
        if (apiFamily == null || apiFamily.isBlank()) {
            throw new IllegalStateException("API family not configured for scope=" + scope);
        }
        return apiFamily;
    }

    private Agent resolveImplementationAgent(final String scope) {
        final ServiceGroup group = this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> Objects.equals(value.getPath(), scope))
                .map(ServicePropertiesProvider.ServiceConfigView::getGroup)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Service scope not found: " + scope));
        return switch (group) {
            case BACKEND -> Agent.IMPLEMENT_BE;
            case FRONTEND -> Agent.IMPLEMENT_FE;
            case TOOL -> throw new IllegalArgumentException("Unsupported service group for implementation lane: " + group);
        };
    }

    private <P extends AgentTicketPayload> AgentTicket<P> ticket(final ReadyToStartLane lane,
                                                                 final P payload,
                                                                 final String scope,
                                                                 final Agent agent) {
        return AgentTicket.<P>builder()
                .id(UUID.randomUUID())
                .ticketId(lane.getTicketId())
                .status(AgentTicketStatus.CREATED)
                .scope(scope)
                .agent(agent)
                .payload(payload)
                .build();
    }

    private void requireExpectedScope(final ReadyToStartLane lane, final String scope) {
        if (scope == null || !Objects.equals(lane.getScope(), scope)) {
            throw new IllegalArgumentException("Completion payload scope mismatch: expected=" + lane.getScope() + ", actual=" + scope);
        }
    }

    private Map<String, Object> requireCompletionPayload(final Map<String, Object> evidence) {
        return this.requireMap(evidence, "completionPayload");
    }

    private Map<String, Object> requireMap(final Map<String, Object> source, final String fieldName) {
        final Object value = source == null ? null : source.get(fieldName);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Missing object field: " + fieldName);
        }
        return this.objectMapper.convertValue(map, MAP_TYPE);
    }

    private List<Map<String, Object>> requireListOfMaps(final Map<String, Object> source, final String fieldName) {
        final Object value = source == null ? null : source.get(fieldName);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing array field: " + fieldName);
        }
        return list.stream()
                .map(this::asMap)
                .toList();
    }

    private Map<String, Object> asMap(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected object item");
        }
        return this.objectMapper.convertValue(map, MAP_TYPE);
    }

    private List<Map<String, Object>> listOfMaps(final Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::asMap).toList();
    }

    private List<Object> listOfObjects(final Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return List.copyOf(list);
    }

    private String requireText(final Map<String, Object> source, final String fieldName) {
        final String value = Objects.toString(source == null ? null : source.get(fieldName), null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing text field: " + fieldName);
        }
        return value;
    }

    private boolean requireBoolean(final Map<String, Object> source, final String fieldName) {
        final Object value = source == null ? null : source.get(fieldName);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("Missing boolean field: " + fieldName);
        }
        return bool;
    }

    private <T> T convert(final Map<String, Object> source, final Class<T> type) {
        return this.objectMapper.convertValue(source, type);
    }

    private record ExecutionContext(
            Map<String, String> apiFamilyByScope,
            Map<String, List<Map<String, Object>>> contractsByScope,
            Map<String, List<Map<String, Object>>> contractsByApiFamily
    ) {
    }

    private static final class ScopeModeHolder {
        private static final String GLOBAL_SCOPE = ScopeMode.GLOBAL_SCOPE;

        private ScopeModeHolder() {
        }
    }
}
