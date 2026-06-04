package com.sitionix.forgeai.application.agentexecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionContractResult;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionEvidence;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionGeneratedArtifact;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionOutput;
import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionPayload;
import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneCompletionSupport {

    private final CompleteAgentTasks completeAgentTasks;
    private final CreateAgentTask createAgentTask;
    private final ValidateApiLaneEvidence validateApiLaneEvidence;
    private final LaneRepository laneRepository;
    private final LaneCompletionContractResolver laneCompletionContractResolver;
    private final ObjectMapper objectMapper;

    public void requireExpectedScope(final ReadyToStartLane lane, final String scope) {
        this.requireExpectedScope(lane.getScope(), scope);
    }

    public void requireExpectedScope(final Lane lane, final String scope) {
        this.requireExpectedScope(lane.getScope(), scope);
    }

    public void requireExpectedScope(final String expectedScope, final String actualScope) {
        if (actualScope == null || !Objects.equals(expectedScope, actualScope)) {
            throw new ScopeMismatchException("Completion payload scope mismatch: expected=" + expectedScope + ", actual=" + actualScope);
        }
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

    public <P extends AgentTicketPayload> AgentTicket<P> targetTicket(final ReadyToStartLane sourceLane,
                                                                      final Lane targetLane,
                                                                      final P payload) {
        return this.ticket(sourceLane, payload, targetLane.getScope(), targetLane.getAgent());
    }

    public void validateProducedLaneInputs(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final LaneCompletionPayload payload = this.completionPayload(completionPayload);
        final List<Lane> targetLanes = this.laneRepository.findCompletionTargetLanes(lane.getLaneId());
        this.validateNoDuplicateOutputs(lane, payload.outputs());
        this.validateNoUnknownOutputs(lane, targetLanes, payload.outputs());
        targetLanes.forEach(targetLane -> this.validateProducedLaneOutput(lane, targetLane, payload.outputs()));
    }

    public void completeProducedLaneInputs(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final List<AgentTicket<? extends AgentTicketPayload>> tickets = new ArrayList<>();
        final List<Lane> lanesToMarkNotNeeded = new ArrayList<>();
        final LaneCompletionPayload payload = this.completionPayload(completionPayload);

        for (final Lane targetLane : this.laneRepository.findCompletionTargetLanes(lane.getLaneId())) {
            final LaneCompletionOutput output = this.optionalOutputForTarget(targetLane, payload.outputs()).orElse(null);
            if (output == null && this.targetAlreadyMarkedNotNeeded(targetLane)) {
                continue;
            }
            if (output == null && !this.requiresOutputForEveryTarget(lane)) {
                continue;
            }
            if (output == null) {
                throw this.missingOutput(lane, targetLane);
            }
            if (!output.isRequired()) {
                lanesToMarkNotNeeded.add(targetLane);
                continue;
            }
            final AgentTicket<? extends AgentTicketPayload> ticket = this.asProducedTicket(lane, targetLane, output);
            this.validateTargetTicket(targetLane, ticket);
            tickets.add(ticket);
        }

        if (!tickets.isEmpty()) {
            this.completeAgentTasks.complete(lane.getLaneId(), tickets);
        }
        for (final Lane targetLane : lanesToMarkNotNeeded) {
            this.createAgentTask.markAsNotNeeded(lane.getLaneId(), targetLane.getScope(), targetLane.getAgent());
        }
        if (tickets.isEmpty() && lanesToMarkNotNeeded.isEmpty()) {
            this.completeAgentTasks.complete(lane.getLaneId(), List.of());
        }
    }

    public LaneCompletionPayload completionPayload(final Map<String, Object> completionPayload) {
        return this.objectMapper.convertValue(completionPayload, LaneCompletionPayload.class);
    }

    public void validateNoOutputs(final Map<String, Object> completionPayload) {
        final LaneCompletionPayload payload = this.completionPayload(completionPayload);
        if (!this.outputs(payload.outputs()).isEmpty()) {
            throw new IllegalArgumentException("Completion payload outputs must be empty for this lane");
        }
    }

    public <P extends AgentTicketPayload> P requireCompletionReport(final Map<String, Object> completionPayload,
                                                                    final Class<P> payloadType) {
        final LaneCompletionPayload payload = this.completionPayload(completionPayload);
        if (payload.report() == null || payload.report().isEmpty()) {
            throw new IllegalArgumentException("Missing completion report payload");
        }
        return this.objectMapper.convertValue(payload.report(), payloadType);
    }

    public void validateCompletionReport(final ReadyToStartLane lane,
                                         final Map<String, Object> completionPayload) {
        final AgentTicketPayload payload = this.requireCompletionReport(lane, completionPayload);
        this.requireExpectedScope(lane, this.payloadScope(payload));
    }

    public AgentTicket<AgentTicketPayload> completionReportTicket(final ReadyToStartLane lane,
                                                                  final Map<String, Object> completionPayload) {
        final AgentTicketPayload payload = this.requireCompletionReport(lane, completionPayload);
        this.requireExpectedScope(lane, this.payloadScope(payload));
        return AgentTicket.<AgentTicketPayload>builder()
                .id(UUID.randomUUID())
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .status(AgentTicketStatus.CONSUMED)
                .scope(lane.getScope())
                .agent(lane.getAgent())
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private AgentTicketPayload requireCompletionReport(final ReadyToStartLane lane,
                                                       final Map<String, Object> completionPayload) {
        final Class<? extends AgentTicketPayload> payloadType =
                this.laneCompletionContractResolver.completionReportPayloadType(lane.getAgent())
                        .orElseThrow(() -> new IllegalArgumentException("Completion report payload is not configured for agent="
                                + lane.getAgent().getId()));
        return this.requireCompletionReport(completionPayload, payloadType);
    }

    private String payloadScope(final AgentTicketPayload payload) {
        try {
            final Method method = payload.getClass().getMethod("getScope");
            final Object value = method.invoke(payload);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Completion report payload must expose getScope(): "
                    + payload.getClass().getSimpleName(), exception);
        }
    }

    private void validateProducedLaneOutput(final ReadyToStartLane sourceLane,
                                            final Lane targetLane,
                                            final List<LaneCompletionOutput> outputs) {
        final LaneCompletionOutput output = this.optionalOutputForTarget(targetLane, outputs).orElse(null);
        if (output == null && this.targetAlreadyMarkedNotNeeded(targetLane)) {
            return;
        }
        if (output == null && !this.requiresOutputForEveryTarget(sourceLane)) {
            return;
        }
        if (output == null) {
            throw this.missingOutput(sourceLane, targetLane);
        }
        if (!output.isRequired()) {
            return;
        }
        if (output.payload() == null) {
            throw new IllegalArgumentException("Missing required output payload: sourceLaneId=" + sourceLane.getLaneId()
                    + ", targetAgent=" + targetLane.getAgent()
                    + ", targetScope=" + targetLane.getScope());
        }
        this.objectMapper.convertValue(
                output.payload(),
                this.laneCompletionContractResolver.inputPayloadType(sourceLane.getAgent(), targetLane.getAgent())
        );
    }

    private AgentTicket<? extends AgentTicketPayload> asProducedTicket(final ReadyToStartLane sourceLane,
                                                                       final Lane targetLane,
                                                                       final LaneCompletionOutput output) {
        final Class<? extends AgentTicketPayload> payloadType =
                this.laneCompletionContractResolver.inputPayloadType(sourceLane.getAgent(), targetLane.getAgent());
        final AgentTicketPayload payload = this.asPayload(output.payload(), payloadType);
        return this.targetTicket(sourceLane, targetLane, payload);
    }

    private AgentTicketPayload asPayload(final Map<String, Object> payload,
                                         final Class<? extends AgentTicketPayload> payloadType) {
        final AgentTicketPayload converted = this.objectMapper.convertValue(payload, payloadType);
        this.preserveOrderedSetFields(payload, converted);
        return converted;
    }

    private void preserveOrderedSetFields(final Map<String, Object> rawPayload,
                                          final AgentTicketPayload convertedPayload) {
        if (rawPayload == null || convertedPayload == null) {
            return;
        }
        for (final Field field : convertedPayload.getClass().getDeclaredFields()) {
            if (!Set.class.isAssignableFrom(field.getType())) {
                continue;
            }
            final Object rawValue = rawPayload.get(field.getName());
            if (!(rawValue instanceof Collection<?> rawCollection)) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(convertedPayload, new LinkedHashSet<>(rawCollection));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Failed to preserve completion payload order for field=" + field.getName(), exception);
            }
        }
    }

    private Optional<LaneCompletionOutput> optionalOutputForTarget(final Lane targetLane,
                                                                  final List<LaneCompletionOutput> outputs) {
        return this.outputs(outputs).stream()
                .filter(output -> Objects.equals(output.agent(), targetLane.getAgent().getId()))
                .filter(output -> Objects.equals(output.scope(), targetLane.getScope()))
                .findFirst();
    }

    private boolean targetAlreadyMarkedNotNeeded(final Lane targetLane) {
        return Objects.equals(targetLane.getStatus(), LaneStatus.NOT_NEEDED);
    }

    private boolean requiresOutputForEveryTarget(final ReadyToStartLane sourceLane) {
        return this.laneCompletionContractResolver.requiresCompletionOutputForEveryTarget(sourceLane.getAgent());
    }

    private RuntimeException missingOutput(final ReadyToStartLane sourceLane,
                                           final Lane targetLane) {
        return new IllegalArgumentException("Missing produced output: sourceLaneId=" + sourceLane.getLaneId()
                + ", targetAgent=" + targetLane.getAgent().getId()
                + ", targetScope=" + targetLane.getScope());
    }

    private void validateNoUnknownOutputs(final ReadyToStartLane sourceLane,
                                          final List<Lane> targetLanes,
                                          final List<LaneCompletionOutput> outputs) {
        for (final LaneCompletionOutput output : this.outputs(outputs)) {
                final Optional<Lane> targetWithSameAgent = targetLanes.stream()
                    .filter(targetLane -> Objects.equals(output.agent(), targetLane.getAgent().getId()))
                    .findFirst();
            final boolean known = targetLanes.stream()
                    .anyMatch(targetLane -> Objects.equals(output.agent(), targetLane.getAgent().getId())
                            && Objects.equals(output.scope(), targetLane.getScope()));
            if (!known) {
                if (targetWithSameAgent.isPresent()) {
                    throw this.scopeMismatch(sourceLane, targetWithSameAgent.get(), output);
                }
                throw new IllegalArgumentException("Completion output does not match a produced lane: sourceLaneId="
                        + sourceLane.getLaneId()
                        + ", outputAgent=" + output.agent()
                        + ", outputScope=" + output.scope());
            }
        }
    }

    private ScopeMismatchException scopeMismatch(final ReadyToStartLane sourceLane,
                                                 final Lane targetLane,
                                                 final LaneCompletionOutput output) {
        return new ScopeMismatchException("Completion output scope mismatch: sourceLaneId=" + sourceLane.getLaneId()
                + ", sourceAgent=" + sourceLane.getAgent().getId()
                + ", targetAgent=" + targetLane.getAgent().getId()
                + ", expectedScope=" + targetLane.getScope()
                + ", actualScope=" + output.scope());
    }

    private void validateNoDuplicateOutputs(final ReadyToStartLane sourceLane,
                                            final List<LaneCompletionOutput> outputs) {
        final Set<String> seenOutputKeys = new LinkedHashSet<>();
        for (final LaneCompletionOutput output : this.outputs(outputs)) {
            final String outputKey = output.agent() + "::" + output.scope();
            if (!seenOutputKeys.add(outputKey)) {
                throw new IllegalArgumentException("Duplicate completion output: sourceLaneId="
                        + sourceLane.getLaneId()
                        + ", outputAgent=" + output.agent()
                        + ", outputScope=" + output.scope());
            }
        }
    }

    private List<LaneCompletionOutput> outputs(final List<LaneCompletionOutput> outputs) {
        return outputs == null ? List.of() : outputs;
    }

    private void validateTargetTicket(final Lane targetLane, final AgentTicket<?> ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("Produced lane task is missing for laneId=" + targetLane.getId());
        }
        if (!Objects.equals(ticket.getAgent(), targetLane.getAgent())) {
            throw new IllegalArgumentException("Produced lane task agent mismatch: laneId=" + targetLane.getId()
                    + ", laneAgent=" + targetLane.getAgent()
                    + ", ticketAgent=" + ticket.getAgent());
        }
        if (!Objects.equals(ticket.getScope(), targetLane.getScope())) {
            throw new IllegalArgumentException("Produced lane task scope mismatch: laneId=" + targetLane.getId()
                    + ", laneScope=" + targetLane.getScope()
                    + ", ticketScope=" + ticket.getScope());
        }
    }

    public void validateApiEvidence(final ReadyToStartLane lane,
                                    final Map<String, Object> payload) {
        final ApiCompletionEvidence evidence = this.completionPayload(payload).apiEvidence();
        if (evidence == null) {
            throw new IllegalArgumentException("Missing API completion evidence");
        }
        final List<ApiCompletionContractResult> contracts =
                evidence.contracts() == null ? List.of() : evidence.contracts();
        final Set<String> contractScopes = contracts.stream()
                .map(ApiCompletionContractResult::scope)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.validateApiLaneEvidence.validate(
                lane.getLaneId(),
                contractScopes,
                ApiLaneEvidencePayload.builder()
                        .prUrl(evidence.prUrl())
                        .repo(evidence.repo())
                        .dependencies(this.apiEvidenceDependencies(contracts))
                        .build()
        );
    }

    private List<ApiLaneEvidenceDependency> apiEvidenceDependencies(
            final List<ApiCompletionContractResult> contracts
    ) {
        final List<ApiLaneEvidenceDependency> dependencies = new ArrayList<>();
        for (final ApiCompletionContractResult contract : contracts) {
            final String scope = contract.scope();
            if (contract.artifacts() == null) {
                continue;
            }
            for (final ApiCompletionGeneratedArtifact artifact : contract.artifacts()) {
                dependencies.add(new ApiLaneEvidenceDependency(
                        scope,
                        artifact.role(),
                        artifact.runId()
                ));
            }
        }
        return dependencies;
    }

}
