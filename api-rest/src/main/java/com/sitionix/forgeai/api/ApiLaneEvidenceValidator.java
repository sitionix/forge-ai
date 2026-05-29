package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.ApiLaneDependencyEvidence;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiLaneEvidenceValidator {

    private final TicketRepository ticketRepository;
    private final AgentTicketRepository agentTicketRepository;

    public void validateRequiredScopeDependencies(final UUID laneId, final CompleteApiLaneRequest request) {
        final Lane lane = this.ticketRepository.findByLaneId(laneId)
                .orElseThrow(() -> new ScopeMismatchException("Api lane not found for laneId=" + laneId));
        final Set<String> requiredScopes = this.requiredScopesFromArchitectApiTasks(lane);
        if (requiredScopes.isEmpty()) {
            return;
        }
        final Set<String> providedScopes = this.scopesFromEvidence(request);
        final Set<String> missingScopes = requiredScopes.stream()
                .filter(scope -> !providedScopes.contains(scope))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (!missingScopes.isEmpty()) {
            throw new ScopeMismatchException("API callback evidence missing required dependency scopes: laneId="
                    + laneId + ", missingScopes=" + missingScopes + ", requiredScopes=" + requiredScopes);
        }
    }

    private Set<String> requiredScopesFromArchitectApiTasks(final Lane lane) {
        if (lane.getInputTaskIds() == null || lane.getInputTaskIds().isEmpty()) {
            return Set.of();
        }
        return lane.getInputTaskIds().stream()
                .map(inputTaskId -> this.agentTicketRepository.findById(inputTaskId, ApiPayload.class))
                .flatMap(Optional::stream)
                .map(AgentTicket::getPayload)
                .filter(Objects::nonNull)
                .filter(payload -> Boolean.TRUE.equals(payload.getRequired()))
                .map(ApiPayload::getScope)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private Set<String> scopesFromEvidence(final CompleteApiLaneRequest request) {
        final List<ApiLaneDependencyEvidence> dependencies = request.getEvidence() == null
                ? List.of()
                : request.getEvidence().getDependencies();
        if (dependencies == null || dependencies.isEmpty()) {
            return Set.of();
        }
        return dependencies.stream()
                .filter(Objects::nonNull)
                .filter(value -> Objects.nonNull(value.getRunId()) && value.getRunId() > 0)
                .map(ApiLaneDependencyEvidence::getScope)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }
}
