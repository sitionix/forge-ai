package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        this.validateAgentCallbackScope(laneId, implementationScope, Agent.IMPLEMENT_BE);
    }

    public void validateImplementFeCallbackScope(final UUID laneId, final String implementationScope) {
        this.validateAgentCallbackScope(laneId, implementationScope, Agent.IMPLEMENT_FE);
    }

    public void validateTestUiCallbackScope(final UUID laneId, final String testScope) {
        this.validateAgentCallbackScope(laneId, testScope, Agent.TEST_UI);
    }

    public void validateAgentCallbackScope(final UUID laneId,
                                           final String requestScope,
                                           final Agent expectedAgent) {
        final String laneIdLabel = expectedAgent.getId();
        final Lane lane = this.requireLane(laneId, laneIdLabel + " lane not found for laneId=");
        if (!Objects.equals(lane.getAgent(), expectedAgent)) {
            throw new ScopeMismatchException(laneIdLabel + " lane type mismatch: laneId=" + laneId
                    + ", laneAgent=" + lane.getAgent()
                    + ", expectedAgent=" + expectedAgent);
        }
        if (Objects.equals(lane.getScope(), requestScope)) {
            return;
        }
        throw new ScopeMismatchException(laneIdLabel + " scope mismatch: laneId=" + laneId
                + ", laneScope=" + lane.getScope()
                + ", requestScope=" + requestScope);
    }

    public boolean validateApiCompletion(final UUID laneId) {
        final Lane lane = this.requireLane(laneId, "Api lane not found for laneId=");
        if (!Objects.equals(lane.getAgent(), Agent.API)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Api lane type mismatch: laneId=" + laneId
                            + ", laneAgent=" + lane.getAgent()
                            + ", expectedAgent=" + Agent.API);
        }
        if (Objects.equals(lane.getStatus(), LaneStatus.COMPLETED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "api lane cannot be completed in current state: laneId=" + laneId
                            + ", laneStatus=" + lane.getStatus());
        }
        return true;
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

    public void validateUnitTestCallbackScope(final UUID laneId, final String testScope) {
        final Lane lane = this.requireLane(laneId, "Unit-test lane not found for laneId=");
        if (Objects.equals(lane.getScope(), testScope)) {
            return;
        }
        throw new ScopeMismatchException("Unit-test scope mismatch: laneId=" + laneId
                + ", laneScope=" + lane.getScope()
                + ", requestScope=" + testScope);
    }

    public Lane validateQaLeadCompletion(final UUID ticketId, final UUID laneId, final String scope) {
        return this.validateCompletion(ticketId, laneId, scope, Agent.QA_LEAD, "QA lead");
    }

    public Lane validateItTestCompletion(final UUID ticketId, final UUID laneId, final String scope) {
        return this.validateCompletion(ticketId, laneId, scope, Agent.TEST_IT, "IT test");
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

    private Lane validateCompletion(final UUID ticketId,
                                    final UUID laneId,
                                    final String scope,
                                    final Agent expectedAgent,
                                    final String laneLabel) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        laneLabel + " ticket not found with ticketId=" + ticketId));
        final Lane lane = ticket.getLanes() == null ? null : ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .orElse(null);
        if (lane == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    laneLabel + " lane not found for ticketId=" + ticketId + ", laneId=" + laneId);
        }

        if (!Objects.equals(lane.getAgent(), expectedAgent)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    laneLabel + " lane type mismatch: laneId=" + laneId
                            + ", laneAgent=" + lane.getAgent()
                            + ", expectedAgent=" + expectedAgent);
        }
        if (!Objects.equals(lane.getScope(), scope)) {
            throw new ScopeMismatchException(laneLabel + " scope mismatch: laneId=" + laneId
                    + ", laneScope=" + lane.getScope()
                    + ", requestScope=" + scope);
        }
        this.validateInProgressStatus(laneId, lane, laneLabel);
        return lane;
    }

    private void validateInProgressStatus(final UUID laneId, final Lane lane, final String laneLabel) {
        if (Objects.equals(lane.getStatus(), LaneStatus.IN_PROGRESS)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                laneLabel + " lane cannot be completed in current state: laneId=" + laneId
                        + ", laneStatus=" + lane.getStatus());
    }
}
