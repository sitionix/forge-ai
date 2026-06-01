package com.sitionix.forgeai.infrastructure.resources.lanestrategy;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceLaneStrategyRepositoryTest {

    @Test
    void givenValidApiStrategy_whenInit_thenLoadAndKeepOrder() {
        final LaneStrategiesProperties properties = new LaneStrategiesProperties();
        properties.setConfigs(Map.of("api", strategy(
                step("preparation", List.of("additional-instructions/preparation-to-work.md")),
                step("contract_update", List.of("additional-instructions/api-contract-rules.md", "additional-instructions/version-rules.md"))
        )));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());
        repository.init();

        final LaneStrategy strategy = repository.findByAgentId("api");
        assertThat(strategy.getSteps()).hasSize(2);
        assertThat(strategy.getSteps().get(0).getId()).isEqualTo("preparation");
        assertThat(strategy.getSteps().get(1).getId()).isEqualTo("contract_update");
    }

    @Test
    void givenDuplicateStepIds_whenInit_thenReject() {
        final LaneStrategiesProperties properties = new LaneStrategiesProperties();
        properties.setConfigs(Map.of("api", strategy(
                step("preparation", List.of("additional-instructions/preparation-to-work.md")),
                step("preparation", List.of("additional-instructions/version-rules.md"))
        )));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate step id");
    }

    @Test
    void givenMissingInstructionRef_whenInit_thenReject() {
        final LaneStrategiesProperties properties = new LaneStrategiesProperties();
        properties.setConfigs(Map.of("api", strategy(
                step("preparation", List.of("additional-instructions/does-not-exist.md"))
        )));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Instruction ref");
    }

    @Test
    void givenDuplicateInstructionRefInsideStep_whenInit_thenReject() {
        final LaneStrategiesProperties properties = new LaneStrategiesProperties();
        properties.setConfigs(Map.of("api", strategy(
                step("preparation", List.of(
                        "additional-instructions/preparation-to-work.md",
                        "additional-instructions/preparation-to-work.md"
                ))
        )));
        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate instruction ref");
    }

    @Test
    void givenEmptySteps_whenInit_thenReject() {
        final LaneStrategiesProperties.StrategyConfig config = new LaneStrategiesProperties.StrategyConfig();
        config.setVersion(1);
        config.setSessionMode("single_session");

        final LaneStrategiesProperties properties = new LaneStrategiesProperties();
        properties.setConfigs(Map.of("api", config));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no steps");
    }

    private static LaneStrategiesProperties.StrategyConfig strategy(final LaneStrategiesProperties.StepConfig... steps) {
        final LaneStrategiesProperties.StrategyConfig config = new LaneStrategiesProperties.StrategyConfig();
        config.setVersion(1);
        config.setSessionMode("single_session");
        config.setSteps(List.of(steps));
        return config;
    }

    private static LaneStrategiesProperties.StepConfig step(final String id, final List<String> refs) {
        final LaneStrategiesProperties.StepConfig step = new LaneStrategiesProperties.StepConfig();
        step.setId(id);
        step.setTitle(id);
        step.setInstructionRefs(refs);
        return step;
    }
}
