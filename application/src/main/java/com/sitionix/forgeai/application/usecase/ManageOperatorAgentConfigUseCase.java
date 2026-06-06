package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageOperatorAgentConfigUseCase implements ManageOperatorAgentConfig {

    private static final String RESTART_REQUIRED_MESSAGE = "Changes are written to source resources. Restart Forge AI to apply them to runtime scheduling and supervised execution.";
    private static final String AGENT_YML_KEY = "agent-yml";
    private static final String LANE_STRATEGIES_YML_KEY = "lane-strategies-yml";
    private static final String INSTRUCTION_KEY_PREFIX = "instruction:";
    private static final String CONTRACT_KEY_PREFIX = "contract:";
    private static final Pattern CONTRACT_TYPE_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    private final AgentPropertiesProvider agentPropertiesProvider;
    private final LaneStrategyRepository laneStrategyRepository;
    private final InstructionRepository instructionRepository;
    private final CompletionPayloadContractRepository completionPayloadContractRepository;
    private final ObjectMapper objectMapper;

    @Override
    public OperatorAgentConfigResponse config() {
        final Path root = this.repoRoot();
        final List<OperatorPayloadContractResourceView> contracts = this.payloadContracts(root);
        final Map<String, OperatorPayloadContractResourceView> contractsByPayloadType = contracts.stream()
                .collect(LinkedHashMap::new, (map, contract) -> map.put(contract.payloadType(), contract), Map::putAll);
        final List<OperatorAgentConfigView> agents = this.agentPropertiesProvider.getAgents().stream()
                .map(agent -> this.agent(agent, contractsByPayloadType))
                .toList();
        final List<OperatorInstructionResourceView> instructions = this.instructions(agents);
        final List<OperatorConfigResourceView> resources = new ArrayList<>();
        resources.add(this.resource(AGENT_YML_KEY, "agent.yml", "yaml", this.agentYamlPath(root)));
        resources.add(this.resource(LANE_STRATEGIES_YML_KEY, "lane-strategies.yml", "yaml", this.laneStrategiesYamlPath(root)));
        instructions.stream()
                .map(instruction -> this.resource(instruction.resourceKey(), instruction.ref(), "markdown", this.instructionPath(root, instruction.ref())))
                .forEach(resources::add);
        contracts.stream()
                .map(contract -> this.resource(contract.resourceKey(), contract.payloadType(), "json", this.contractPath(root, contract.payloadType())))
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
        final Path root = this.repoRoot();
        final ResourceTarget target = this.target(root, request.resourceKey());
        this.validateContent(target, request.content());
        this.write(target.path(), request.content());
        return this.resource(target.resourceKey(), target.label(), target.resourceType(), target.path());
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

    private List<OperatorPayloadContractResourceView> payloadContracts(final Path root) {
        final Path contractsPath = this.contractsPath(root);
        if (!Files.isDirectory(contractsPath)) {
            return List.of();
        }
        try (var paths = Files.list(contractsPath)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(this::payloadContract)
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list payload contracts", e);
        }
    }

    private OperatorPayloadContractResourceView payloadContract(final Path path) {
        final String payloadType = path.getFileName().toString().replaceFirst("\\.json$", "");
        final String content = this.read(path);
        final CompletionPayloadObjectContract contract = this.completionPayloadContractRepository.findByTypeName(payloadType);
        return new OperatorPayloadContractResourceView(payloadType, CONTRACT_KEY_PREFIX + payloadType, contract, content);
    }

    private ResourceTarget target(final Path root, final String resourceKey) {
        if (AGENT_YML_KEY.equals(resourceKey)) {
            return new ResourceTarget(resourceKey, "agent.yml", "yaml", this.agentYamlPath(root));
        }
        if (LANE_STRATEGIES_YML_KEY.equals(resourceKey)) {
            return new ResourceTarget(resourceKey, "lane-strategies.yml", "yaml", this.laneStrategiesYamlPath(root));
        }
        if (resourceKey.startsWith(INSTRUCTION_KEY_PREFIX)) {
            final String ref = resourceKey.substring(INSTRUCTION_KEY_PREFIX.length());
            return new ResourceTarget(resourceKey, ref, "markdown", this.instructionPath(root, ref));
        }
        if (resourceKey.startsWith(CONTRACT_KEY_PREFIX)) {
            final String payloadType = resourceKey.substring(CONTRACT_KEY_PREFIX.length());
            return new ResourceTarget(resourceKey, payloadType, "json", this.contractPath(root, payloadType));
        }
        throw new IllegalArgumentException("Unsupported config resourceKey: " + resourceKey);
    }

    private OperatorConfigResourceView resource(final String resourceKey, final String label, final String resourceType, final Path path) {
        return new OperatorConfigResourceView(
                resourceKey,
                label,
                resourceType,
                path.toString(),
                Files.isRegularFile(path) && Files.isWritable(path),
                this.read(path)
        );
    }

    private void validateContent(final ResourceTarget target, final String content) {
        if (content.isBlank()) {
            throw new IllegalArgumentException("Resource content must not be blank: " + target.resourceKey());
        }
        if ("yaml".equals(target.resourceType())) {
            final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
            if (yaml.getObject() == null) {
                throw new IllegalArgumentException("Invalid YAML content: " + target.resourceKey());
            }
        }
        if ("json".equals(target.resourceType())) {
            try {
                this.objectMapper.readTree(content);
            } catch (final IOException e) {
                throw new IllegalArgumentException("Invalid JSON content: " + target.resourceKey(), e);
            }
        }
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read resource: " + path, e);
        }
    }

    private void write(final Path path, final String content) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Resource file does not exist: " + path);
        }
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write resource: " + path, e);
        }
    }

    private Path repoRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("pom.xml")) && Files.isDirectory(path.resolve("boot/src/main/resources"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Unable to locate forge-ai repository root from user.dir=" + System.getProperty("user.dir"));
    }

    private Path agentYamlPath(final Path root) {
        return root.resolve("boot/src/main/resources/agent.yml").normalize();
    }

    private Path laneStrategiesYamlPath(final Path root) {
        return root.resolve("boot/src/main/resources/lane-strategies.yml").normalize();
    }

    private Path instructionPath(final Path root, final String ref) {
        final Path base = root.resolve("infrastructure/resources/src/main/resources/instructions").normalize();
        final String normalizedRef = ref.startsWith("instructions/")
                ? ref.substring("instructions/".length())
                : ref;
        final Path path = base.resolve(normalizedRef).normalize();
        if (!path.startsWith(base) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Unsupported instruction ref: " + ref);
        }
        return path;
    }

    private Path contractsPath(final Path root) {
        return root.resolve("infrastructure/resources/src/main/resources/completion-payload-contracts").normalize();
    }

    private Path contractPath(final Path root, final String payloadType) {
        if (!CONTRACT_TYPE_PATTERN.matcher(payloadType).matches()) {
            throw new IllegalArgumentException("Unsupported payload contract type: " + payloadType);
        }
        final Path path = this.contractsPath(root).resolve(payloadType + ".json").normalize();
        if (!path.startsWith(this.contractsPath(root)) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Unsupported payload contract type: " + payloadType);
        }
        return path;
    }

    private record ResourceTarget(String resourceKey, String label, String resourceType, Path path) {
    }
}
