package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.operator.OperatorConfigResource;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import com.sitionix.forgeai.domain.repository.OperatorConfigResourceRepository;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorAgentCompletionView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorAgentConfigResponse;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorAgentConfigView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorAgentInputPayloadView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceSaveRequest;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorInstructionResourceView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorLaneStrategyStepView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorLaneStrategyView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorPayloadContractResourceView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorPayloadContractSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageOperatorAgentConfigUseCase implements ManageOperatorAgentConfig {

    private static final String RESTART_REQUIRED_MESSAGE = "Changes are written to source resources. Restart Forge AI to apply them to runtime scheduling and supervised execution.";
    private static final String INSTRUCTION_KEY_PREFIX = "instruction:";
    private static final String CONTRACT_KEY_PREFIX = "contract:";

    private final AgentPropertiesProvider agentPropertiesProvider;
    private final LaneStrategyRepository laneStrategyRepository;
    private final InstructionRepository instructionRepository;
    private final CompletionPayloadContractRepository completionPayloadContractRepository;
    private final OperatorConfigResourceRepository operatorConfigResourceRepository;

    @Override
    public OperatorAgentConfigResponse config() {
        final List<OperatorPayloadContractResourceView> contracts = this.payloadContracts();
        final Map<String, OperatorPayloadContractResourceView> contractsByPayloadType = contracts.stream()
                .collect(LinkedHashMap::new, (map, contract) -> map.put(contract.payloadType(), contract), Map::putAll);
        final List<OperatorAgentConfigView> agents = this.agentPropertiesProvider.getAgents().stream()
                .map(agent -> this.agent(agent, contractsByPayloadType))
                .toList();
        final List<OperatorInstructionResourceView> instructions = this.instructions(agents);
        final List<OperatorConfigResourceView> resources = new ArrayList<>();
        resources.add(this.resource(this.operatorConfigResourceRepository.agentYaml()));
        resources.add(this.resource(this.operatorConfigResourceRepository.laneStrategiesYaml()));
        instructions.stream()
                .map(instruction -> this.resource(this.operatorConfigResourceRepository.instruction(instruction.ref())))
                .forEach(resources::add);
        contracts.stream()
                .map(contract -> this.resource(this.operatorConfigResourceRepository.contract(contract.payloadType())))
                .forEach(resources::add);
        return new OperatorAgentConfigResponse(agents, instructions, contracts, resources, RESTART_REQUIRED_MESSAGE);
    }

    @Override
    public OperatorConfigResourceView saveResource(final OperatorConfigResourceSaveRequest request) {
        if (request == null || request.resourceKey() == null || request.resourceKey().isBlank()) {
            throw new IllegalArgumentException("resourceKey is required");
        }
        if (request.content() == null) {
            throw new IllegalArgumentException("content is required");
        }
        return this.resource(this.operatorConfigResourceRepository.save(request.resourceKey(), request.content()));
    }

    private OperatorAgentConfigView agent(final AgentPropertiesProvider.AgentConfigView agent,
                                          final Map<String, OperatorPayloadContractResourceView> contractsByPayloadType) {
        final LaneStrategy strategy = this.laneStrategy(agent.getId());
        final List<OperatorAgentInputPayloadView> inputPayloads = agent.getInputPayloadTypes().entrySet().stream()
                .map(entry -> new OperatorAgentInputPayloadView(
                        entry.getKey().getId(),
                        entry.getValue().getId(),
                        entry.getValue().getPayloadClass().getSimpleName()
                ))
                .toList();
        final List<OperatorPayloadContractSummary> payloadContracts = this.payloadContractSummaries(agent, inputPayloads, contractsByPayloadType);
        return new OperatorAgentConfigView(
                agent.getId(),
                agent.isEnabled(),
                agent.getScopeMode() == null ? null : agent.getScopeMode().getId(),
                agent.getGroups().stream().map(ServiceGroup::name).map(String::toLowerCase).sorted().toList(),
                agent.getDependsOn().stream().map(Agent::getId).toList(),
                agent.getProduces().stream().map(Agent::getId).toList(),
                inputPayloads,
                new OperatorAgentCompletionView(
                        agent.writesProducedLaneOutputs(),
                        agent.requiresApiCompletionEvidence(),
                        agent.requiresCompletionOutputForEveryTarget(),
                        agent.getCompletionReportPayloadType().map(AgentTicketPayloadType::getId).orElse(null)
                ),
                this.strategy(strategy),
                payloadContracts
        );
    }

    private List<OperatorPayloadContractSummary> payloadContractSummaries(final AgentPropertiesProvider.AgentConfigView agent,
                                                                          final List<OperatorAgentInputPayloadView> inputPayloads,
                                                                          final Map<String, OperatorPayloadContractResourceView> contractsByPayloadType) {
        final Set<String> payloadTypes = new LinkedHashSet<>();
        inputPayloads.forEach(payload -> payloadTypes.add(payload.payloadClass()));
        agent.getCompletionReportPayloadType()
                .map(AgentTicketPayloadType::getPayloadClass)
                .map(Class::getSimpleName)
                .ifPresent(payloadTypes::add);
        if (agent.requiresApiCompletionEvidence()) {
            payloadTypes.add("ApiCompletionEvidence");
        }
        return payloadTypes.stream()
                .map(contractsByPayloadType::get)
                .filter(Objects::nonNull)
                .map(contract -> new OperatorPayloadContractSummary(
                        contract.payloadType(),
                        contract.payloadType(),
                        contract.contract().description(),
                        contract.resourceKey()
                ))
                .toList();
    }

    private OperatorLaneStrategyView strategy(final LaneStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        return new OperatorLaneStrategyView(
                strategy.getAgentId(),
                strategy.getVersion(),
                strategy.getSessionMode(),
                strategy.getSteps().stream()
                        .sorted(Comparator.comparingInt(LaneStrategyStep::getOrder))
                        .map(step -> new OperatorLaneStrategyStepView(
                                step.getOrder(),
                                step.getId(),
                                step.getTitle(),
                                step.getTaskPlaceholder(),
                                step.getCompletionContractPlaceholder(),
                                step.getInstructionRefs()
                        ))
                        .toList()
        );
    }

    private LaneStrategy laneStrategy(final String agentId) {
        return this.laneStrategyRepository.findByAgentId(agentId);
    }

    private List<OperatorInstructionResourceView> instructions(final List<OperatorAgentConfigView> agents) {
        final Set<String> refs = new LinkedHashSet<>(this.instructionRepository.findSharedInstructionRefs());
        agents.stream()
                .map(OperatorAgentConfigView::laneStrategy)
                .filter(Objects::nonNull)
                .flatMap(strategy -> strategy.steps().stream())
                .flatMap(step -> step.instructionRefs().stream())
                .forEach(refs::add);
        return refs.stream()
                .map(ref -> new OperatorInstructionResourceView(ref, INSTRUCTION_KEY_PREFIX + ref, this.instructionRepository.findInstructionTextByRef(ref)))
                .toList();
    }

    private List<OperatorPayloadContractResourceView> payloadContracts() {
        return this.operatorConfigResourceRepository.contracts().stream()
                .map(this::payloadContract)
                .toList();
    }

    private OperatorPayloadContractResourceView payloadContract(final OperatorConfigResource resource) {
        final String payloadType = resource.label();
        final CompletionPayloadObjectContract contract = this.completionPayloadContractRepository.findByTypeName(payloadType);
        return new OperatorPayloadContractResourceView(payloadType, CONTRACT_KEY_PREFIX + payloadType, contract, resource.content());
    }

    private OperatorConfigResourceView resource(final OperatorConfigResource resource) {
        return new OperatorConfigResourceView(
                resource.resourceKey(),
                resource.label(),
                resource.resourceType(),
                resource.path(),
                resource.writable(),
                resource.content()
        );
    }
}
