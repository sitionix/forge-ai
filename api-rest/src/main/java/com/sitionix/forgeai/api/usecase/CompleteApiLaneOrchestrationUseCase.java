package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.ApiLaneContractResult;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneGeneratedArtifact;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.LaneRepository;
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
public class CompleteApiLaneOrchestrationUseCase {

    private final LaneRepository laneRepository;
    private final CompleteAgentTasks completeAgentTasks;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final LaneScopeValidator laneScopeValidator;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteApiLaneRequest request) {
        final Set<String> relevantScopes = this.laneScopeValidator.resolveRelevantApiScopes(laneId, request.getContracts().stream()
                .map(ApiLaneContractResult::getScope)
                .collect(Collectors.toSet()));
        final List<ApiLaneContractResult> filteredContracts = request.getContracts().stream()
                .filter(value -> relevantScopes.contains(value.getScope()))
                .toList();
        final ExecutionContext context = this.buildContext(filteredContracts);
        this.findImplementationLanes(laneId).forEach(targetLane -> this.createTaskForTargetLane(ticketId, laneId, request.getSummary(), targetLane, context));
    }

    private void createTaskForTargetLane(final UUID ticketId,
                                         final UUID sourceLaneId,
                                         final String summary,
                                         final Lane targetLane,
                                         final ExecutionContext context) {
        if (Objects.equals(targetLane.getAgent(), Agent.IMPLEMENT_BE)) {
            final List<ApiLaneContractResult> contracts = context.contractsByScope().getOrDefault(targetLane.getScope(), List.of());
            if (contracts.isEmpty()) {
                throw new IllegalStateException("No API contracts found for backend scope=" + targetLane.getScope());
            }
            this.completeAgentTasks.complete(sourceLaneId, List.of(this.asImplementTicket(ticketId, targetLane.getScope(), summary, contracts, Agent.IMPLEMENT_BE, false)));
            return;
        }

        final String frontendApiFamily = this.apiFamilyByScope(targetLane.getScope(), context.apiFamilyByScope());
        final List<ApiLaneContractResult> contracts = context.contractsByApiFamily().getOrDefault(frontendApiFamily, List.of());
        if (contracts.isEmpty()) {
            throw new IllegalStateException("No API contracts found for frontend scope=" + targetLane.getScope()
                    + ", apiFamily=" + frontendApiFamily);
        }
        this.completeAgentTasks.complete(sourceLaneId, List.of(this.asImplementTicket(ticketId, targetLane.getScope(), summary, contracts, Agent.IMPLEMENT_FE, true)));
    }

    private List<Lane> findImplementationLanes(final UUID laneId) {
        return this.laneRepository.findProducedLanes(laneId).stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.IMPLEMENT_BE) || Objects.equals(value.getAgent(), Agent.IMPLEMENT_FE))
                .toList();
    }

    private ExecutionContext buildContext(final List<ApiLaneContractResult> contracts) {
        final Map<String, String> apiFamilyByScope = this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> Objects.nonNull(value.getPath()))
                .filter(value -> Objects.nonNull(value.getContractRefs()))
                .filter(value -> Objects.nonNull(value.getContractRefs().get("api")))
                .collect(Collectors.toMap(
                        ServicePropertiesProvider.ServiceConfigView::getPath,
                        value -> value.getContractRefs().get("api").getApiFamily(),
                        (left, right) -> left
                ));
        final Map<String, List<ApiLaneContractResult>> contractsByScope = contracts.stream()
                .collect(Collectors.groupingBy(ApiLaneContractResult::getScope));
        final Map<String, List<ApiLaneContractResult>> contractsByApiFamily = contracts.stream()
                .collect(Collectors.groupingBy(value -> this.apiFamilyByScope(value.getScope(), apiFamilyByScope)));
        return new ExecutionContext(apiFamilyByScope, contractsByScope, contractsByApiFamily);
    }

    private AgentTicket<?> asImplementTicket(final UUID ticketId,
                                             final String scope,
                                             final String summary,
                                             final List<ApiLaneContractResult> contracts,
                                             final Agent agent,
                                             final boolean frontendOnly) {
        final PayloadData payloadData = this.asPayloadData(scope, summary, contracts, frontendOnly);
        if (Objects.equals(agent, Agent.IMPLEMENT_BE)) {
            return AgentTicket.<ImplementBePayload>builder()
                    .id(UUID.randomUUID())
                    .ticketId(ticketId)
                    .status(AgentTicketStatus.CREATED)
                    .scope(scope)
                    .agent(agent)
                    .payload(ImplementBePayload.builder()
                            .task(payloadData.task())
                            .scope(payloadData.scope())
                            .summary(payloadData.summary())
                            .requirements(payloadData.requirements())
                            .constraints(payloadData.constraints())
                            .nonGoals(Set.of())
                            .architectureDecision("Use generated API artifacts directly.")
                            .dependencies(payloadData.dependencies())
                            .acceptanceNotes(payloadData.acceptanceNotes())
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
                        .task(payloadData.task())
                        .scope(payloadData.scope())
                        .summary(payloadData.summary())
                        .requirements(payloadData.requirements())
                        .constraints(payloadData.constraints())
                        .nonGoals(Set.of())
                        .architectureDecision("Use generated API artifacts directly.")
                        .dependencies(payloadData.dependencies())
                        .acceptanceNotes(payloadData.acceptanceNotes())
                        .risks(Set.of())
                        .build())
                .build();
    }

    private String apiFamilyByScope(final String scope, final Map<String, String> apiFamilyByScope) {
        final String apiFamily = apiFamilyByScope.get(scope);
        if (Objects.isNull(apiFamily) || apiFamily.isBlank()) {
            throw new IllegalStateException("API family not configured for scope=" + scope);
        }
        return apiFamily;
    }

    private PayloadData asPayloadData(final String scope,
                                      final String summary,
                                      final List<ApiLaneContractResult> contracts,
                                      final boolean frontendOnly) {
        return new PayloadData(
                "Implement API contract integration for " + scope,
                scope,
                summary,
                this.contractRequirements(contracts),
                this.contractNotes(contracts),
                this.contractDependencies(contracts, frontendOnly),
                this.contractEvidenceNotes(contracts, frontendOnly)
        );
    }

    private Set<String> contractRequirements(final List<ApiLaneContractResult> contracts) {
        return contracts.stream()
                .map(value -> value.getMethod() + " " + value.getPath() + " (" + value.getOperationId() + ")")
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractNotes(final List<ApiLaneContractResult> contracts) {
        return contracts.stream()
                .map(ApiLaneContractResult::getNotes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractDependencies(final List<ApiLaneContractResult> contracts, final boolean frontendOnly) {
        return contracts.stream()
                .map(ApiLaneContractResult::getArtifacts)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(value -> this.isArtifactForTarget(value, frontendOnly))
                .map(ApiLaneGeneratedArtifact::getDependency)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractEvidenceNotes(final List<ApiLaneContractResult> contracts, final boolean frontendOnly) {
        return contracts.stream()
                .map(ApiLaneContractResult::getArtifacts)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(value -> this.isArtifactForTarget(value, frontendOnly))
                .map(ApiLaneGeneratedArtifact::getNotes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isArtifactForTarget(final ApiLaneGeneratedArtifact artifact, final boolean frontendOnly) {
        final boolean frontendArtifact = Objects.equals(artifact.getKind(), ApiLaneGeneratedArtifact.KindEnum.NPM)
                || Objects.equals(artifact.getRole(), ApiLaneGeneratedArtifact.RoleEnum.FRONTEND_CONTRACT);
        return frontendOnly == frontendArtifact;
    }

    private record ExecutionContext(
            Map<String, String> apiFamilyByScope,
            Map<String, List<ApiLaneContractResult>> contractsByScope,
            Map<String, List<ApiLaneContractResult>> contractsByApiFamily
    ) {
    }

    private record PayloadData(
            String task,
            String scope,
            String summary,
            Set<String> requirements,
            Set<String> constraints,
            Set<String> dependencies,
            Set<String> acceptanceNotes
    ) {
    }
}
