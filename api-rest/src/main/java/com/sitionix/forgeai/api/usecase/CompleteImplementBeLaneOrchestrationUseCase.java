package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeChangedFileDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBePersistenceChangeDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteImplementBeLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final CreateAgentTask createAgentTask;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteImplementBeLaneRequestDTO request) {
        this.laneScopeValidator.validateImplementBeCallbackScope(laneId, request.getScope());

        final Set<String> changedFiles = this.changedFiles(request);
        if (changedFiles.isEmpty()) {
            this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_UNIT);
        } else {
            this.createAgentTask.create(this.asTestUnitTicket(ticketId, request, changedFiles), laneId);
        }

        final Set<String> integrationFlows = this.integrationFlows(request);
        final Set<String> persistenceChanges = this.persistenceChanges(request);
        if (integrationFlows.isEmpty() && persistenceChanges.isEmpty()) {
            this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_IT);
            return;
        }
        this.createAgentTask.create(this.asTestItTicket(ticketId, request, integrationFlows, persistenceChanges), laneId);
    }

    private AgentTicket<TestUnitPayload> asTestUnitTicket(final UUID ticketId,
                                                          final CompleteImplementBeLaneRequestDTO request,
                                                          final Set<String> changedFiles) {
        return AgentTicket.<TestUnitPayload>builder()
                .id(UUID.randomUUID())
                .ticketId(ticketId)
                .status(AgentTicketStatus.CREATED)
                .scope(request.getScope())
                .agent(Agent.TEST_UNIT)
                .payload(TestUnitPayload.builder()
                        .task("Write unit tests for backend changed files in " + request.getScope())
                        .scope(request.getScope())
                        .summary(request.getSummary())
                        .changedFiles(changedFiles)
                        .build())
                .build();
    }

    private AgentTicket<TestItPayload> asTestItTicket(final UUID ticketId,
                                                      final CompleteImplementBeLaneRequestDTO request,
                                                      final Set<String> integrationFlows,
                                                      final Set<String> persistenceChanges) {
        return AgentTicket.<TestItPayload>builder()
                .id(UUID.randomUUID())
                .ticketId(ticketId)
                .status(AgentTicketStatus.CREATED)
                .scope(request.getScope())
                .agent(Agent.TEST_IT)
                .payload(TestItPayload.builder()
                        .task("Write integration tests for backend integration and persistence changes in " + request.getScope())
                        .scope(request.getScope())
                        .summary(request.getSummary())
                        .integrationFlows(integrationFlows)
                        .persistenceChanges(persistenceChanges)
                        .build())
                .build();
    }

    private Set<String> changedFiles(final CompleteImplementBeLaneRequestDTO request) {
        if (Objects.isNull(request.getChangedFiles())) {
            return Set.of();
        }
        return request.getChangedFiles().stream()
                .map(this::asChangedFile)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> integrationFlows(final CompleteImplementBeLaneRequestDTO request) {
        if (Objects.isNull(request.getIntegrationFlows())) {
            return Set.of();
        }
        return request.getIntegrationFlows().stream()
                .map(this::asIntegrationFlow)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> persistenceChanges(final CompleteImplementBeLaneRequestDTO request) {
        if (Objects.isNull(request.getPersistenceChanges())) {
            return Set.of();
        }
        return request.getPersistenceChanges().stream()
                .map(this::asPersistenceChange)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String asChangedFile(final ImplementBeChangedFileDTO value) {
        if (Objects.isNull(value) || Objects.isNull(value.getPath()) || value.getPath().isBlank()) {
            return null;
        }
        if (Objects.isNull(value.getReason()) || value.getReason().isBlank()) {
            return value.getPath();
        }
        return value.getPath() + " :: " + value.getReason();
    }

    private String asIntegrationFlow(final ImplementBeIntegrationFlowDTO value) {
        if (Objects.isNull(value) || Objects.isNull(value.getName()) || value.getName().isBlank()) {
            return null;
        }
        return value.getName()
                + " | " + value.getMethod()
                + " " + value.getPath()
                + " | " + value.getOperationId()
                + " | " + value.getSummary();
    }

    private String asPersistenceChange(final ImplementBePersistenceChangeDTO value) {
        if (Objects.isNull(value) || Objects.isNull(value.getName()) || value.getName().isBlank()) {
            return null;
        }
        return value.getType() + " | " + value.getName() + " | " + value.getSummary();
    }
}
