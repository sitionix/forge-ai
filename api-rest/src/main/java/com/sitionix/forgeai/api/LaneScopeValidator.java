package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneScopeValidator {

    private final TicketRepository ticketRepository;
    private final LaneRepository laneRepository;

    public void validateAnalyzerCallbackScope(final UUID laneId, final String architectScope, final String qaLeadScope) {
        final Lane lane = this.requireLane(laneId, "Analyzer lane not found for laneId=");
        final String laneScope = lane.getScope();

        if (Objects.nonNull(architectScope) && Objects.nonNull(qaLeadScope) && !Objects.equals(architectScope, qaLeadScope)) {
            throw new ScopeMismatchException("Analyzer callback payload has different handoff scopes: architect="
                    + architectScope + ", qaLead=" + qaLeadScope + ", laneScope=" + laneScope);
        }
        if (Objects.nonNull(architectScope) && !Objects.equals(architectScope, laneScope)) {
            throw new ScopeMismatchException("Analyzer callback scope mismatch: payload scope="
                    + architectScope + ", laneScope=" + laneScope + ", laneId=" + laneId);
        }
        if (Objects.nonNull(qaLeadScope) && !Objects.equals(qaLeadScope, laneScope)) {
            throw new ScopeMismatchException("Analyzer callback scope mismatch: payload scope="
                    + qaLeadScope + ", laneScope=" + laneScope + ", laneId=" + laneId);
        }
    }

    public void validateArchitectCallbackScope(final UUID laneId, final String implementationScope) {
        final Lane lane = this.requireLane(laneId, "Architect lane not found for laneId=");
        if (Objects.equals(lane.getScope(), implementationScope)) {
            return;
        }
        throw new ScopeMismatchException("Implementation scope mismatch: laneId=" + laneId
                + ", laneScope=" + lane.getScope()
                + ", requestScope=" + implementationScope);
    }

    public void validateImplementBeCallbackScope(final UUID laneId, final String implementationScope) {
        final Lane lane = this.requireLane(laneId, "Implement-be lane not found for laneId=");
        if (Objects.equals(lane.getScope(), implementationScope)) {
            return;
        }
        throw new ScopeMismatchException("Implement-be scope mismatch: laneId=" + laneId
                + ", laneScope=" + lane.getScope()
                + ", requestScope=" + implementationScope);
    }

    public void validateQaLeadCallbackScope(final UUID laneId, final String testScope) {
        final Lane lane = this.requireLane(laneId, "Qa-lead lane not found for laneId=");
        if (Objects.equals(lane.getScope(), testScope)) {
            return;
        }
        throw new ScopeMismatchException("QA lead scope mismatch: laneId=" + laneId
                + ", laneScope=" + lane.getScope()
                + ", requestScope=" + testScope);
    }

    public Set<String> resolveRelevantApiScopes(final UUID laneId, final Set<String> contractScopes) {
        final Set<String> implementationScopes = this.laneRepository.findProducedLanes(laneId).stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.IMPLEMENT_BE) || Objects.equals(value.getAgent(), Agent.IMPLEMENT_FE))
                .map(Lane::getScope)
                .collect(Collectors.toSet());
        final Set<String> relevantScopes = contractScopes.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .filter(implementationScopes::contains)
                .collect(Collectors.toSet());
        if (relevantScopes.isEmpty()) {
            throw new ScopeMismatchException("API callback does not contain contracts for produced implementation scopes: laneId="
                    + laneId + ", callbackScopes=" + contractScopes + ", producedScopes=" + implementationScopes);
        }
        return relevantScopes;
    }

    private Lane requireLane(final UUID laneId, final String messagePrefix) {
        return this.ticketRepository.findByLaneId(laneId)
                .orElseThrow(() -> new ScopeMismatchException(messagePrefix + laneId));
    }
}
