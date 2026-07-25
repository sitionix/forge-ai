package com.sitionix.forgeai.infrastructure.resources.lanestrategy;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStepType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(LaneStrategiesProperties.class)
public class ResourceLaneStrategyRepository implements LaneStrategyRepository {

    private static final String TASKS_PLACEHOLDER = "TASKS";
    private static final String COMPLETION_PAYLOAD_CONTRACT_PLACEHOLDER = "COMPLETION_PAYLOAD_CONTRACT";

    private final LaneStrategiesProperties properties;
    private final ResourceLoader resourceLoader;
    private Map<String, LaneStrategy> strategies;

    @PostConstruct
    public void init() {
        this.strategies = new HashMap<>();
        this.validateCommonInstructionRefs();
        this.properties.getConfigs().forEach((agentId, cfg) -> {
            this.validateAgent(agentId);
            this.validateSteps(agentId, cfg);
            final List<LaneStrategyStep> steps = IntStream.range(0, cfg.getSteps().size())
                    .mapToObj(i -> {
                        final LaneStrategiesProperties.StepConfig step = cfg.getSteps().get(i);
                        return LaneStrategyStep.builder()
                                .id(step.getId())
                                .title(step.getTitle())
                                .order(i + 1)
                                .type(this.stepType(agentId, step))
                                .handler(step.getHandler())
                                .taskPlaceholder(step.getTaskPlaceholder())
                                .completionContractPlaceholder(step.getCompletionContractPlaceholder())
                                .validator(step.getValidator())
                                .instructionRefs(List.copyOf(step.getInstructionRefs()))
                                .build();
                    })
                    .toList();
            this.strategies.put(agentId, LaneStrategy.builder()
                    .agentId(agentId)
                    .version(cfg.getVersion())
                    .sessionMode(cfg.getSessionMode())
                    .steps(steps)
                    .build());
        });
        this.validateRequiredStrategies();
    }

    @Override
    public LaneStrategy findByAgentId(final String agentId) {
        final LaneStrategy strategy = this.strategies.get(agentId);
        if (strategy == null) {
            throw new IllegalArgumentException("Lane strategy not found for agentId: " + agentId);
        }
        return strategy;
    }

    private void validateAgent(final String agentId) {
        Agent.byId(agentId);
    }

    private void validateSteps(final String agentId, final LaneStrategiesProperties.StrategyConfig cfg) {
        if (cfg.getSteps() == null || cfg.getSteps().isEmpty()) {
            throw new IllegalStateException("Lane strategy has no steps for agentId=" + agentId);
        }
        final Set<String> stepIds = new HashSet<>();
        cfg.getSteps().forEach(step -> {
            if (!stepIds.add(step.getId())) {
                throw new IllegalStateException("Duplicate step id '" + step.getId() + "' for agentId=" + agentId);
            }
            this.validateTaskPlaceholder(agentId, step);
            this.validateCompletionContractPlaceholder(agentId, step);
            this.validateStepHandler(agentId, step);
            final Set<String> refs = new HashSet<>();
            step.getInstructionRefs().forEach(ref -> {
                if (!refs.add(ref)) {
                    throw new IllegalStateException("Duplicate instruction ref '" + ref + "' in step='" + step.getId() + "'");
                }
                this.validateInstructionRef(ref);
            });
        });
    }

    private LaneStrategyStepType stepType(final String agentId, final LaneStrategiesProperties.StepConfig step) {
        if (step.getType() == null || step.getType().isBlank()) {
            return LaneStrategyStepType.AGENT;
        }
        try {
            return LaneStrategyStepType.valueOf(step.getType().trim().toUpperCase());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported step type '" + step.getType()
                    + "' for agentId=" + agentId + ", stepId=" + step.getId(), ex);
        }
    }

    private void validateStepHandler(final String agentId, final LaneStrategiesProperties.StepConfig step) {
        final LaneStrategyStepType type = this.stepType(agentId, step);
        final boolean hasHandler = step.getHandler() != null && !step.getHandler().isBlank();
        if (LaneStrategyStepType.ORCHESTRATOR.equals(type) && !hasHandler) {
            throw new IllegalStateException("Orchestrator step requires handler: agentId="
                    + agentId + ", stepId=" + step.getId());
        }
        if (LaneStrategyStepType.AGENT.equals(type) && hasHandler) {
            throw new IllegalStateException("Agent step must not define handler: agentId="
                    + agentId + ", stepId=" + step.getId());
        }
    }

    private void validateTaskPlaceholder(final String agentId, final LaneStrategiesProperties.StepConfig step) {
        if (step.getTaskPlaceholder() == null || step.getTaskPlaceholder().isBlank()) {
            return;
        }
        if (!TASKS_PLACEHOLDER.equals(step.getTaskPlaceholder())) {
            throw new IllegalStateException("Unsupported task placeholder '" + step.getTaskPlaceholder()
                    + "' for agentId=" + agentId + ", stepId=" + step.getId());
        }
    }

    private void validateCompletionContractPlaceholder(final String agentId, final LaneStrategiesProperties.StepConfig step) {
        if (step.getCompletionContractPlaceholder() == null || step.getCompletionContractPlaceholder().isBlank()) {
            return;
        }
        if (!COMPLETION_PAYLOAD_CONTRACT_PLACEHOLDER.equals(step.getCompletionContractPlaceholder())) {
            throw new IllegalStateException("Unsupported completion contract placeholder '" + step.getCompletionContractPlaceholder()
                    + "' for agentId=" + agentId + ", stepId=" + step.getId());
        }
        if (!"completion".equals(step.getId())) {
            throw new IllegalStateException("Completion contract placeholder is only supported on completion step: agentId="
                    + agentId + ", stepId=" + step.getId());
        }
    }

    private void validateCommonInstructionRefs() {
        if (this.properties.getCommonInstructionRefs() == null || this.properties.getCommonInstructionRefs().isEmpty()) {
            throw new IllegalStateException("Missing common instruction refs for lane strategies");
        }
        final Set<String> refs = new HashSet<>();
        this.properties.getCommonInstructionRefs().forEach(ref -> {
            if (!refs.add(ref)) {
                throw new IllegalStateException("Duplicate common instruction ref '" + ref + "'");
            }
            this.validateInstructionRef(ref);
        });
    }

    private void validateInstructionRef(final String ref) {
        final String normalized = ref.startsWith("instructions/") ? ref : "instructions/" + ref;
        final Resource resource = this.resourceLoader.getResource("classpath:" + normalized);
        try {
            if (!resource.exists() || resource.getInputStream().readAllBytes().length == 0) {
                throw new IllegalStateException("Instruction ref not found or empty: " + ref);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read instruction ref: " + ref, e);
        }
    }

    private void validateRequiredStrategies() {
        Arrays.stream(Agent.values())
                .map(Agent::getId)
                .forEach(agentId -> {
                    if (!this.strategies.containsKey(agentId)) {
                        throw new IllegalStateException("Missing lane strategy for executable agentId=" + agentId);
                    }
                });
    }
}
