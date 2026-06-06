package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ContractRefContext;
import com.sitionix.forgeai.domain.model.codex.DbContext;
import com.sitionix.forgeai.domain.model.codex.DeployContext;
import com.sitionix.forgeai.domain.model.codex.DeployUnitContext;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.codex.ServiceScopeContext;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PrepareAgentExecutionInputUseCase {

    private final TicketRepository ticketRepository;
    private final ServicePropertiesProvider props;

    public AgentExecutionInput<AgentTicketPayload> execute(final ReadyToStartLane lane) {
        if (!this.ticketRepository.moveLaneToInProgressIfReady(lane.getLaneId())) {
            throw new IllegalStateException("Lane is not ready to start or already started: laneId=" + lane.getLaneId());
        }
        return this.executeClaimed(lane);
    }

    public AgentExecutionInput<AgentTicketPayload> executeClaimed(final ReadyToStartLane lane) {
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(lane.getTicketId())
                .ticket(lane.getTicketKey())
                .laneId(lane.getLaneId())
                .build();
    }

    public AgentExecutionInput<AgentTicketPayload> enrichWithTasks(
            final ReadyToStartLane lane,
            final AgentExecutionInput<AgentTicketPayload> input,
            final Set<? extends AgentTicketPayload> tasks
    ) {
        return input.toBuilder()
                .tasks(new LinkedHashSet<>(tasks))
                .scope(this.scopeContext(lane))
                .build();
    }

    private ScopeContext scopeContext(final ReadyToStartLane lane) {
        final ServiceScopeContext currentService = this.serviceScopeContext(lane.getServiceId(), lane.getScope());
        return ScopeContext.builder()
                .scope(lane.getScope())
                .service(currentService)
                .relatedServices(this.relatedServiceContexts(lane))
                .build();
    }

    private Set<ServiceScopeContext> relatedServiceContexts(final ReadyToStartLane lane) {
        if (!Objects.equals(ScopeMode.GLOBAL_SCOPE, lane.getScope())) {
            return Collections.emptySet();
        }
        final Ticket ticket = this.ticketRepository.findById(lane.getTicketId()).orElse(null);
        if (ticket == null || ticket.getLanes() == null) {
            return Collections.emptySet();
        }
        final Set<String> serviceIds = ticket.getLanes().stream()
                .map(Lane::getServiceId)
                .filter(Objects::nonNull)
                .filter(serviceId -> !Objects.equals("global", serviceId))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        final Set<ServiceScopeContext> contexts = new LinkedHashSet<>();
        for (final String serviceId : serviceIds) {
            contexts.add(this.serviceScopeContext(serviceId, null));
        }
        return contexts;
    }

    private ServiceScopeContext serviceScopeContext(final String serviceId, final String fallbackScope) {
        final ServicePropertiesProvider.ServiceConfigView service = this.props.getServices().get(serviceId);
        if (service == null) {
            return ServiceScopeContext.builder()
                    .serviceId(serviceId)
                    .scope(fallbackScope)
                    .build();
        }
        return ServiceScopeContext.builder()
                .serviceId(serviceId)
                .scope(service.getPath())
                .label(service.getLabel())
                .path(service.getPath())
                .group(service.getGroup())
                .tags(this.copyList(service.getTags()))
                .tests(this.copyList(service.getTests()))
                .domainKeywords(this.copyList(service.getDomainKeywords()))
                .ownBusinessAreas(this.copyList(service.getOwnsBusinessAreas()))
                .architectureRefs(this.copyList(service.getArchitectureRefs()))
                .contractRefs(this.contractRefs(service.getContractRefs()))
                .deploy(this.deploy(service.getDeploy()))
                .db(this.db(service.getDb()))
                .build();
    }

    private Map<String, ContractRefContext> contractRefs(final Map<String, ServicePropertiesProvider.ContractRefView> refs) {
        if (refs == null || refs.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, ContractRefContext> result = new LinkedHashMap<>();
        refs.forEach((name, ref) -> result.put(name, this.contractRef(ref)));
        return result;
    }

    private ContractRefContext contractRef(final ServicePropertiesProvider.ContractRefView ref) {
        if (ref == null) {
            return null;
        }
        return ContractRefContext.builder()
                .sourceRepo(ref.getSourceRepo())
                .apiFamily(ref.getApiFamily())
                .eventFamily(ref.getEventFamily())
                .serviceCode(ref.getServiceCode())
                .root(ref.getRoot())
                .schemas(this.copyList(ref.getSchemas()))
                .operations(this.copyList(ref.getOperations()))
                .topics(this.copyList(ref.getTopics()))
                .payloads(this.copyList(ref.getPayloads()))
                .generatedArtifacts(this.copyList(ref.getGeneratedArtifacts()))
                .consumerArtifacts(this.copyList(ref.getConsumerArtifacts()))
                .frontendPackages(this.copyList(ref.getFrontendPackages()))
                .build();
    }

    private DeployContext deploy(final ServicePropertiesProvider.DeployConfigView deploy) {
        if (deploy == null) {
            return null;
        }
        return DeployContext.builder()
                .type(deploy.getType())
                .repo(deploy.getRepo())
                .service(this.deployUnit(deploy.getService()))
                .db(this.deployUnit(deploy.getDb()))
                .build();
    }

    private DeployUnitContext deployUnit(final ServicePropertiesProvider.DeployUnitConfigView deployUnit) {
        if (deployUnit == null) {
            return null;
        }
        return DeployUnitContext.builder()
                .name(deployUnit.getName())
                .workflowName(deployUnit.getWorkflowName())
                .workflowEvent(deployUnit.getWorkflowEvent())
                .build();
    }

    private DbContext db(final ServicePropertiesProvider.DbConfigView db) {
        if (db == null) {
            return null;
        }
        return DbContext.builder()
                .required(db.getRequired())
                .type(db.getType())
                .mode(db.getMode())
                .key(db.getKey())
                .build();
    }

    private List<String> copyList(final List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
