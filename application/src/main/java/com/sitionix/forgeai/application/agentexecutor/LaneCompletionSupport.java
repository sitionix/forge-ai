package com.sitionix.forgeai.application.agentexecutor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
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
public class LaneCompletionSupport {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CompleteAgentTasks completeAgentTasks;
    private final CreateAgentTask createAgentTask;
    private final ValidateApiLaneEvidence validateApiLaneEvidence;
    private final LaneRepository laneRepository;
    private final AgentTicketRepository agentTicketRepository;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final ObjectMapper objectMapper;

    public void requireExpectedScope(final ReadyToStartLane lane, final String scope) {
        if (scope == null || !Objects.equals(lane.getScope(), scope)) {
            throw new IllegalArgumentException("Completion payload scope mismatch: expected=" + lane.getScope() + ", actual=" + scope);
        }
    }

    public Map<String, Object> requireMap(final Map<String, Object> source, final String fieldName) {
        final Object value = source == null ? null : source.get(fieldName);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Missing object field: " + fieldName);
        }
        return this.objectMapper.convertValue(map, MAP_TYPE);
    }

    public List<Map<String, Object>> requireListOfMaps(final Map<String, Object> source, final String fieldName) {
        final Object value = source == null ? null : source.get(fieldName);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing array field: " + fieldName);
        }
        return list.stream().map(this::asMap).toList();
    }

    public String requireText(final Map<String, Object> source, final String fieldName) {
        final String value = Objects.toString(source == null ? null : source.get(fieldName), null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing text field: " + fieldName);
        }
        return value;
    }

    public boolean requireBoolean(final Map<String, Object> source, final String fieldName) {
        final Object value = source == null ? null : source.get(fieldName);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("Missing boolean field: " + fieldName);
        }
        return bool;
    }

    public <T> T convert(final Map<String, Object> source, final Class<T> type) {
        return this.objectMapper.convertValue(source, type);
    }

    public <P extends AgentTicketPayload> AgentTicket<P> ticket(final ReadyToStartLane lane,
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

    public Agent resolveImplementationAgent(final String scope) {
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

    public <P extends AgentTicketPayload> void createOptionalArchitectLaneTask(final ReadyToStartLane lane,
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

    public <P extends AgentTicketPayload> void routeQaTestLane(final ReadyToStartLane lane,
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

    public void validateApiEvidence(final ReadyToStartLane lane,
                                    final Map<String, Object> payload,
                                    final List<Map<String, Object>> contracts) {
        final Set<String> callbackScopes = contracts.stream()
                .map(contract -> Objects.toString(contract.get("scope"), null))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.validateApiLaneEvidence.validate(
                lane.getLaneId(),
                callbackScopes,
                com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload.builder()
                        .prUrl(Objects.toString(payload.get("prUrl"), null))
                        .repo(Objects.toString(payload.get("repo"), null))
                        .dependencies(this.apiEvidenceDependencies(contracts))
                        .build()
        );
    }

    public ExecutionContext buildApiExecutionContext(final List<Map<String, Object>> contracts) {
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

    public List<Lane> findProducedImplementationLanes(final UUID laneId) {
        return this.laneRepository.findProducedLanes(laneId).stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.IMPLEMENT_BE) || Objects.equals(value.getAgent(), Agent.IMPLEMENT_FE))
                .toList();
    }

    public void createApiImplementationTask(final ReadyToStartLane lane,
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

    public String globalScope() {
        return ScopeMode.GLOBAL_SCOPE;
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

    public record ExecutionContext(
            Map<String, String> apiFamilyByScope,
            Map<String, List<Map<String, Object>>> contractsByScope,
            Map<String, List<Map<String, Object>>> contractsByApiFamily
    ) {
    }
}
