package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionCommands;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionConflictException;
import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CompleteLaneCallbacks;
import com.sitionix.forgeai.domain.usecase.CompleteReviewerTask;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompleteLaneCallbacksUseCase implements CompleteLaneCallbacks {

    private final TicketRepository ticketRepository;
    private final LaneRepository laneRepository;
    private final CompleteAgentTasks completeAgentTasks;
    private final CompleteReviewerTask completeReviewerTask;
    private final CompleteAgentLane completeAgentLane;
    private final CreateAgentTask createAgentTask;
    private final AgentTicketRepository agentTicketRepository;
    private final ValidateApiLaneEvidence validateApiLaneEvidence;
    private final ServicePropertiesProvider servicePropertiesProvider;

    @Override
    public void completeAnalyzerLane(final LaneCompletionCommands.Analyzer command) {
        this.validateAnalyzerCallbackScope(command.laneId(), command.architectScope(), command.qaLeadScope());
        this.completeAgentTasks.complete(command.laneId(), List.of(command.architectTicket(), command.qaLeadTicket()));
    }

    @Override
    public void completeArchitectLane(final LaneCompletionCommands.Architect command) {
        this.validateArchitectCallbackScope(command.laneId(), command.implementationScope());
        final Agent implementationAgent = this.resolveImplementationAgent(command.implementationScope());
        if (Agent.IMPLEMENT_BE.equals(implementationAgent)) {
            this.completeAgentTasks.complete(command.laneId(), List.of(command.implementBeTicket()));
        } else {
            this.completeAgentTasks.complete(command.laneId(), List.of(command.implementFeTicket()));
        }
        this.completeOrMarkNotNeeded(command.laneId(), Agent.API, command.apiTicket());
        this.completeOrMarkNotNeeded(command.laneId(), Agent.EVENT, command.eventTicket());
    }

    @Override
    public void completeApiLane(final LaneCompletionCommands.Api command) {
        this.validateApiCompletion(command.laneId());
        final Set<String> callbackScopes = command.contracts().stream()
                .map(LaneCompletionCommands.ApiContractResult::scope)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        final Set<String> relevantScopes = this.resolveRelevantApiScopes(command.laneId(), callbackScopes);
        this.validateApiLaneEvidence.validate(command.laneId(), callbackScopes, command.evidence());

        final List<LaneCompletionCommands.ApiContractResult> filteredContracts = command.contracts().stream()
                .filter(value -> relevantScopes.contains(value.scope()))
                .toList();
        final ExecutionContext context = this.buildContext(filteredContracts);
        this.findImplementationLanes(command.laneId())
                .forEach(targetLane -> this.createTaskForTargetLane(command.ticketId(), command.laneId(), command.summary(), targetLane, context));
    }

    @Override
    public void completeImplementBeLane(final LaneCompletionCommands.ImplementBe command) {
        this.validateAgentCallbackScope(command.laneId(), command.scope(), Agent.IMPLEMENT_BE);
        this.completeAgentTasks.complete(command.laneId(), List.of(command.testUnitTicket(), command.testItTicket()));
    }

    @Override
    public void completeImplementFeLane(final LaneCompletionCommands.ImplementFe command) {
        this.validateAgentCallbackScope(command.laneId(), command.scope(), Agent.IMPLEMENT_FE);
        this.completeAgentTasks.complete(command.laneId(), List.of(command.testUiTicket()));
    }

    @Override
    public void completeQaLeadLane(final LaneCompletionCommands.QaLead command) {
        this.validateCompletion(command.ticketId(), command.laneId(), command.scope(), Agent.QA_LEAD, "QA lead");
        this.routeTestLane(command.laneId(), command.scope(), command.unitTestRequired(), Agent.TEST_UNIT, command.testUnitTicket());
        this.routeTestLane(command.laneId(), command.scope(), command.integrationTestRequired(), Agent.TEST_IT, command.testItTicket());
        this.routeTestLane(command.laneId(), command.scope(), command.uiTestRequired(), Agent.TEST_UI, command.testUiTicket());
    }

    @Override
    public void completeItTestLane(final LaneCompletionCommands.ItTest command) {
        this.validateCompletion(command.ticketId(), command.laneId(), command.scope(), Agent.TEST_IT, "IT test");
        this.agentTicketRepository.save(command.completionReport());
        this.completeAgentLane.completeAndPrepareAgents(command.laneId());
    }

    @Override
    public void completeUiTestLane(final LaneCompletionCommands.UiTest command) {
        this.validateAgentCallbackScope(command.laneId(), command.scope(), Agent.TEST_UI);
        this.completeAgentTasks.complete(command.laneId(), List.of());
    }

    @Override
    public void completeUnitTestLane(final LaneCompletionCommands.UnitTest command) {
        this.validateAgentCallbackScope(command.laneId(), command.scope(), Agent.TEST_UNIT, "Unit-test");
        this.completeAgentTasks.complete(command.laneId(), List.of(command.reviewerTicket()));
    }

    @Override
    public void completeReviewerLane(final LaneCompletionCommands.Reviewer command) {
        this.completeReviewerTask.complete(command.ticketId());
    }

    private void completeOrMarkNotNeeded(final UUID laneId,
                                         final Agent targetAgent,
                                         final AgentTicket<?> ticket) {
        if (ticket != null) {
            this.completeAgentTasks.complete(laneId, List.of(ticket));
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, ScopeMode.GLOBAL_SCOPE, targetAgent);
    }

    private void routeTestLane(final UUID laneId,
                               final String scope,
                               final boolean required,
                               final Agent agent,
                               final AgentTicket<?> ticket) {
        final Optional<?> lane = this.laneRepository.findLaneToProduceOptional(laneId, scope, agent);
        if (lane.isEmpty()) {
            return;
        }
        if (required) {
            this.createAgentTask.create(ticket, laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, scope, agent);
    }

    private void createTaskForTargetLane(final UUID ticketId,
                                         final UUID sourceLaneId,
                                         final String summary,
                                         final Lane targetLane,
                                         final ExecutionContext context) {
        if (Objects.equals(targetLane.getAgent(), Agent.IMPLEMENT_BE)) {
            final List<LaneCompletionCommands.ApiContractResult> contracts = context.contractsByScope().getOrDefault(targetLane.getScope(), List.of());
            if (contracts.isEmpty()) {
                return;
            }
            this.completeAgentTasks.complete(sourceLaneId, List.of(this.asImplementTicket(ticketId, targetLane.getScope(), summary, contracts, Agent.IMPLEMENT_BE, false)));
            return;
        }

        final String frontendApiFamily = this.apiFamilyByScope(targetLane.getScope(), context.apiFamilyByScope());
        final List<LaneCompletionCommands.ApiContractResult> contracts = context.contractsByApiFamily().getOrDefault(frontendApiFamily, List.of());
        if (contracts.isEmpty()) {
            return;
        }
        this.completeAgentTasks.complete(sourceLaneId, List.of(this.asImplementTicket(ticketId, targetLane.getScope(), summary, contracts, Agent.IMPLEMENT_FE, true)));
    }

    private List<Lane> findImplementationLanes(final UUID laneId) {
        return this.laneRepository.findProducedLanes(laneId).stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.IMPLEMENT_BE) || Objects.equals(value.getAgent(), Agent.IMPLEMENT_FE))
                .toList();
    }

    private ExecutionContext buildContext(final List<LaneCompletionCommands.ApiContractResult> contracts) {
        final Map<String, String> apiFamilyByScope = this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> Objects.nonNull(value.getPath()))
                .filter(value -> Objects.nonNull(value.getContractRefs()))
                .filter(value -> Objects.nonNull(value.getContractRefs().get("api")))
                .collect(Collectors.toMap(
                        ServicePropertiesProvider.ServiceConfigView::getPath,
                        value -> value.getContractRefs().get("api").getApiFamily(),
                        (left, right) -> left
                ));
        final Map<String, List<LaneCompletionCommands.ApiContractResult>> contractsByScope = contracts.stream()
                .collect(Collectors.groupingBy(LaneCompletionCommands.ApiContractResult::scope));
        final Map<String, List<LaneCompletionCommands.ApiContractResult>> contractsByApiFamily = contracts.stream()
                .collect(Collectors.groupingBy(value -> this.apiFamilyByScope(value.scope(), apiFamilyByScope)));
        return new ExecutionContext(apiFamilyByScope, contractsByScope, contractsByApiFamily);
    }

    private AgentTicket<?> asImplementTicket(final UUID ticketId,
                                             final String scope,
                                             final String summary,
                                             final List<LaneCompletionCommands.ApiContractResult> contracts,
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

    private PayloadData asPayloadData(final String scope,
                                      final String summary,
                                      final List<LaneCompletionCommands.ApiContractResult> contracts,
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

    private Set<String> contractRequirements(final List<LaneCompletionCommands.ApiContractResult> contracts) {
        return contracts.stream()
                .map(value -> value.method() + " " + value.path() + " (" + value.operationId() + ")")
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractNotes(final List<LaneCompletionCommands.ApiContractResult> contracts) {
        return contracts.stream()
                .map(LaneCompletionCommands.ApiContractResult::notes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractDependencies(final List<LaneCompletionCommands.ApiContractResult> contracts, final boolean frontendOnly) {
        return contracts.stream()
                .map(LaneCompletionCommands.ApiContractResult::artifacts)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(value -> this.isArtifactForTarget(value, frontendOnly))
                .map(LaneCompletionCommands.ApiGeneratedArtifact::dependency)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> contractEvidenceNotes(final List<LaneCompletionCommands.ApiContractResult> contracts, final boolean frontendOnly) {
        return contracts.stream()
                .map(LaneCompletionCommands.ApiContractResult::artifacts)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(value -> this.isArtifactForTarget(value, frontendOnly))
                .map(LaneCompletionCommands.ApiGeneratedArtifact::notes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isArtifactForTarget(final LaneCompletionCommands.ApiGeneratedArtifact artifact, final boolean frontendOnly) {
        final boolean frontendArtifact = Objects.equals(artifact.kind(), "npm")
                || Objects.equals(artifact.role(), "FRONTEND_CONTRACT");
        return frontendOnly == frontendArtifact;
    }

    private Agent resolveImplementationAgent(final String scope) {
        final ServiceGroup group = this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> Objects.equals(value.getPath(), scope))
                .map(ServicePropertiesProvider.ServiceConfigView::getGroup)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Service scope not found: " + scope));
        if (ServiceGroup.BACKEND.equals(group)) {
            return Agent.IMPLEMENT_BE;
        }
        if (ServiceGroup.FRONTEND.equals(group)) {
            return Agent.IMPLEMENT_FE;
        }
        throw new IllegalArgumentException("Unsupported service group for implementation lane: " + group);
    }

    private String apiFamilyByScope(final String scope, final Map<String, String> apiFamilyByScope) {
        final String apiFamily = apiFamilyByScope.get(scope);
        if (Objects.isNull(apiFamily) || apiFamily.isBlank()) {
            throw new IllegalStateException("API family not configured for scope=" + scope);
        }
        return apiFamily;
    }

    private void validateAnalyzerCallbackScope(final UUID laneId, final String architectScope, final String qaLeadScope) {
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

    private void validateArchitectCallbackScope(final UUID laneId, final String implementationScope) {
        final Lane lane = this.requireLane(laneId, "Architect lane not found for laneId=");
        if (Objects.equals(lane.getScope(), implementationScope)) {
            return;
        }
        throw new ScopeMismatchException("Implementation scope mismatch: laneId=" + laneId
                + ", laneScope=" + lane.getScope()
                + ", requestScope=" + implementationScope);
    }

    private void validateAgentCallbackScope(final UUID laneId,
                                            final String requestScope,
                                            final Agent expectedAgent) {
        this.validateAgentCallbackScope(laneId, requestScope, expectedAgent, expectedAgent.getId());
    }

    private void validateAgentCallbackScope(final UUID laneId,
                                            final String requestScope,
                                            final Agent expectedAgent,
                                            final String laneIdLabel) {
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

    private void validateApiCompletion(final UUID laneId) {
        final Lane lane = this.requireLane(laneId, "Api lane not found for laneId=");
        if (!Objects.equals(lane.getAgent(), Agent.API)) {
            throw new LaneCompletionConflictException(
                    "Api lane type mismatch: laneId=" + laneId
                            + ", laneAgent=" + lane.getAgent()
                            + ", expectedAgent=" + Agent.API);
        }
        if (Objects.equals(lane.getStatus(), LaneStatus.COMPLETED)) {
            throw new LaneCompletionConflictException(
                    "api lane cannot be completed in current state: laneId=" + laneId
                            + ", laneStatus=" + lane.getStatus());
        }
    }

    private Set<String> resolveRelevantApiScopes(final UUID laneId, final Set<String> contractScopes) {
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
                .orElseThrow(() -> new LaneCompletionConflictException(
                        laneLabel + " ticket not found with ticketId=" + ticketId));
        final Lane lane = ticket.getLanes() == null ? null : ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .orElse(null);
        if (lane == null) {
            throw new LaneCompletionConflictException(
                    laneLabel + " lane not found for ticketId=" + ticketId + ", laneId=" + laneId);
        }

        if (!Objects.equals(lane.getAgent(), expectedAgent)) {
            throw new LaneCompletionConflictException(
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
        throw new LaneCompletionConflictException(
                laneLabel + " lane cannot be completed in current state: laneId=" + laneId
                        + ", laneStatus=" + lane.getStatus());
    }

    private record ExecutionContext(
            Map<String, String> apiFamilyByScope,
            Map<String, List<LaneCompletionCommands.ApiContractResult>> contractsByScope,
            Map<String, List<LaneCompletionCommands.ApiContractResult>> contractsByApiFamily
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
