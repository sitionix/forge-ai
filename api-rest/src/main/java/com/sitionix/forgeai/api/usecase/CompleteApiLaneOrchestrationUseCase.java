package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.ApiLaneContractResult;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneGeneratedArtifact;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteApiLaneOrchestrationUseCase {

    private final LaneRepository laneRepository;
    private final CreateAgentTask createAgentTask;
    private final ServicePropertiesProvider servicePropertiesProvider;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteApiLaneRequest request) {
        final Map<String, ServicePropertiesProvider.ServiceConfigView> servicesByPath = this.servicePropertiesProvider.getServices().values().stream()
                .collect(Collectors.toMap(ServicePropertiesProvider.ServiceConfigView::getPath, Function.identity()));
        final Map<String, String> apiFamilyByScope = servicesByPath.values().stream()
                .filter(value -> Objects.nonNull(value.getContractRefs()))
                .filter(value -> Objects.nonNull(value.getContractRefs().get("api")))
                .collect(Collectors.toMap(
                        ServicePropertiesProvider.ServiceConfigView::getPath,
                        value -> value.getContractRefs().get("api").getApiFamily(),
                        (left, right) -> left
                ));
        final Map<String, List<ApiLaneContractResult>> contractsByScope = request.getContracts().stream()
                .collect(Collectors.groupingBy(ApiLaneContractResult::getScope));
        final Map<String, List<ApiLaneContractResult>> contractsByApiFamily = request.getContracts().stream()
                .collect(Collectors.groupingBy(value -> this.apiFamilyByScope(value.getScope(), apiFamilyByScope)));

        this.laneRepository.findProducedLanes(laneId).stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.IMPLEMENT_BE) || Objects.equals(value.getAgent(), Agent.IMPLEMENT_FE))
                .forEach(targetLane -> this.createTaskForTargetLane(ticketId, laneId, request.getSummary(), targetLane, contractsByScope, contractsByApiFamily, apiFamilyByScope));
    }

    private void createTaskForTargetLane(final UUID ticketId,
                                         final UUID sourceLaneId,
                                         final String summary,
                                         final Lane targetLane,
                                         final Map<String, List<ApiLaneContractResult>> contractsByScope,
                                         final Map<String, List<ApiLaneContractResult>> contractsByApiFamily,
                                         final Map<String, String> apiFamilyByScope) {
        if (Objects.equals(targetLane.getAgent(), Agent.IMPLEMENT_BE)) {
            final List<ApiLaneContractResult> contracts = contractsByScope.getOrDefault(targetLane.getScope(), List.of());
            if (contracts.isEmpty()) {
                throw new IllegalStateException("No API contracts found for backend scope=" + targetLane.getScope());
            }
            final AgentTicket<ImplementBePayload> ticket = AgentTicket.<ImplementBePayload>builder()
                    .id(UUID.randomUUID())
                    .ticketId(ticketId)
                    .status(AgentTicketStatus.CREATED)
                    .scope(targetLane.getScope())
                    .agent(Agent.IMPLEMENT_BE)
                    .payload(this.asImplementBePayload(targetLane.getScope(), summary, contracts))
                    .build();
            this.createAgentTask.create(ticket, sourceLaneId);
            return;
        }

        final String frontendApiFamily = this.apiFamilyByScope(targetLane.getScope(), apiFamilyByScope);
        final List<ApiLaneContractResult> contracts = contractsByApiFamily.getOrDefault(frontendApiFamily, List.of());
        if (contracts.isEmpty()) {
            throw new IllegalStateException("No API contracts found for frontend scope=" + targetLane.getScope()
                    + ", apiFamily=" + frontendApiFamily);
        }
        final AgentTicket<ImplementFePayload> ticket = AgentTicket.<ImplementFePayload>builder()
                .id(UUID.randomUUID())
                .ticketId(ticketId)
                .status(AgentTicketStatus.CREATED)
                .scope(targetLane.getScope())
                .agent(Agent.IMPLEMENT_FE)
                .payload(this.asImplementFePayload(targetLane.getScope(), summary, contracts))
                .build();
        this.createAgentTask.create(ticket, sourceLaneId);
    }

    private String apiFamilyByScope(final String scope, final Map<String, String> apiFamilyByScope) {
        final String apiFamily = apiFamilyByScope.get(scope);
        if (Objects.isNull(apiFamily) || apiFamily.isBlank()) {
            throw new IllegalStateException("API family not configured for scope=" + scope);
        }
        return apiFamily;
    }

    private ImplementBePayload asImplementBePayload(final String scope,
                                                    final String summary,
                                                    final List<ApiLaneContractResult> contracts) {
        return ImplementBePayload.builder()
                .task("Implement API contract integration for " + scope)
                .scope(scope)
                .summary(summary)
                .requirements(this.contractRequirements(contracts))
                .constraints(this.contractNotes(contracts))
                .nonGoals(Set.of())
                .architectureDecision("Use generated API artifacts directly.")
                .dependencies(this.contractDependencies(contracts, false))
                .acceptanceNotes(this.contractEvidenceNotes(contracts, false))
                .risks(Set.of())
                .build();
    }

    private ImplementFePayload asImplementFePayload(final String scope,
                                                    final String summary,
                                                    final List<ApiLaneContractResult> contracts) {
        return ImplementFePayload.builder()
                .task("Implement API contract integration for " + scope)
                .scope(scope)
                .summary(summary)
                .requirements(this.contractRequirements(contracts))
                .constraints(this.contractNotes(contracts))
                .nonGoals(Set.of())
                .architectureDecision("Use generated API artifacts directly.")
                .dependencies(this.contractDependencies(contracts, true))
                .acceptanceNotes(this.contractEvidenceNotes(contracts, true))
                .risks(Set.of())
                .build();
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
}
