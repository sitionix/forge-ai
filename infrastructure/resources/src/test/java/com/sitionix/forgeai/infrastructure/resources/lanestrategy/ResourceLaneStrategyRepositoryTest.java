package com.sitionix.forgeai.infrastructure.resources.lanestrategy;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceLaneStrategyRepositoryTest {

    @Test
    void givenValidStrategiesForAllExecutableAgents_whenInit_thenLoadAndKeepOrder() {
        final LaneStrategiesProperties properties = baseProperties();
        final Map<String, LaneStrategiesProperties.StrategyConfig> configs = new LinkedHashMap<>();
        configs.put("analyzer", strategy(step("scope_slicing", "Scope Slicing", null, List.of("additional-instructions/scope-context-usage.md", "lane-instructions/analyzer/scope-slicing.md"))));
        configs.put("architect", strategy(step("input_normalization", "Input Normalization", "TASKS", List.of("lane-instructions/architect/input-normalization.md"))));
        configs.put("api", strategy(
                stepWithValidator("preparation", "Preparation", "gitPreparation", List.of("additional-instructions/preparation-to-work.md")),
                step("contract_changes", "Contract Changes", "TASKS", List.of("additional-instructions/api-contract-rules.md"))
        ));
        configs.put("qa_lead", strategy(step("qa_context", "QA Context", "TASKS", List.of("lane-instructions/qa_lead/qa-context.md"))));
        configs.put("implement_be", strategy(step("backend_context", "Backend Context", "TASKS", List.of("lane-instructions/implement_be/backend-context.md"))));
        configs.put("implement_fe", strategy(step("frontend_context", "Frontend Context", "TASKS", List.of("lane-instructions/implement_fe/frontend-context.md"))));
        configs.put("test_unit", strategy(step("unit_test_context", "Unit Test Context", "TASKS", List.of("lane-instructions/test_unit/unit-test-context.md"))));
        configs.put("test_it", strategy(step("it_test_context", "IT Test Context", "TASKS", List.of("lane-instructions/test_it/it-test-context.md"))));
        configs.put("test_ui", strategy(step("ui_test_context", "UI Test Context", "TASKS", List.of("lane-instructions/test_ui/ui-test-context.md"))));
        configs.put("reviewer", strategy(step("review_context", "Review Context", null, List.of("lane-instructions/reviewer/review-context.md"))));
        configs.put("event", strategy(
                step("preparation", "Preparation", null, List.of("additional-instructions/preparation-to-work.md")),
                step("contract_changes", "Contract Changes", "TASKS", List.of("additional-instructions/event-contract-rules.md"))
        ));
        properties.setConfigs(new LinkedHashMap<>(configs));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());
        repository.init();

        final LaneStrategy strategy = repository.findByAgentId("api");
        assertThat(strategy.getSteps()).hasSize(2);
        assertThat(strategy.getSteps().get(0).getId()).isEqualTo("preparation");
        assertThat(strategy.getSteps().get(0).getValidator()).isEqualTo("gitPreparation");
        assertThat(strategy.getSteps().get(1).getId()).isEqualTo("contract_changes");
        assertThat(strategy.getSteps().get(1).getTaskPlaceholder()).isEqualTo("TASKS");
    }

    @Test
    void givenCompletionContractPlaceholder_whenInit_thenLoadOnlyOnCompletionStep() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                step("preparation", "Preparation", null, List.of("additional-instructions/preparation-to-work.md")),
                step("completion", "Completion", null, "COMPLETION_PAYLOAD_CONTRACT", List.of("lane-instructions/api/completion-content.md"))
        ));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());
        repository.init();

        final LaneStrategy strategy = repository.findByAgentId("api");
        assertThat(strategy.getSteps())
                .filteredOn(step -> "COMPLETION_PAYLOAD_CONTRACT".equals(step.getCompletionContractPlaceholder()))
                .extracting(LaneStrategyStep::getId)
                .containsExactly("completion");
    }

    @Test
    void givenMissingRequiredAgentStrategy_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(Map.of(
                "api", strategy(step("preparation", "Preparation", null, List.of("additional-instructions/preparation-to-work.md")))
        )));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing lane strategy");
    }

    @Test
    void givenMissingCommonInstructionRefs_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setCommonInstructionRefs(List.of());
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("common instruction refs");
    }

    @Test
    void givenDuplicateStepIds_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                step("preparation", "Preparation", null, List.of("additional-instructions/preparation-to-work.md")),
                step("preparation", "Preparation", null, List.of("additional-instructions/version-rules.md"))
        ));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate step id");
    }

    @Test
    void givenMissingInstructionRef_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                step("preparation", "Preparation", null, List.of("additional-instructions/does-not-exist.md"))
        ));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Instruction ref");
    }

    @Test
    void givenDuplicateInstructionRefInsideStep_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                step("preparation", "Preparation", null, List.of(
                        "additional-instructions/preparation-to-work.md",
                        "additional-instructions/preparation-to-work.md"
                ))
        ));
        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate instruction ref");
    }

    @Test
    void givenUnsupportedTaskPlaceholder_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                step("contract_changes", "Contract Changes", "WRONG", List.of("additional-instructions/api-contract-rules.md"))
        ));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported task placeholder");
    }

    @Test
    void givenUnsupportedCompletionContractPlaceholder_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                step("completion", "Completion", null, "WRONG", List.of("lane-instructions/api/completion-content.md"))
        ));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported completion contract placeholder");
    }

    @Test
    void givenCompletionContractPlaceholderOnNonCompletionStep_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                step("contract_changes", "Contract Changes", "TASKS", "COMPLETION_PAYLOAD_CONTRACT", List.of("additional-instructions/api-contract-rules.md"))
        ));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only supported on completion step");
    }

    @Test
    void givenValidatorWithoutEvidenceType_whenInit_thenLoad() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        properties.getConfigs().put("api", strategy(
                stepWithValidator("preparation", "Preparation", "gitPreparation", List.of("additional-instructions/preparation-to-work.md"))
        ));

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        repository.init();

        assertThat(repository.findByAgentId("api").getSteps().getFirst().getValidator()).isEqualTo("gitPreparation");
    }

    @Test
    void givenEmptySteps_whenInit_thenReject() {
        final LaneStrategiesProperties properties = baseProperties();
        properties.setConfigs(new LinkedHashMap<>(baseStrategyMap()));
        final LaneStrategiesProperties.StrategyConfig config = new LaneStrategiesProperties.StrategyConfig();
        config.setVersion(1);
        config.setSessionMode("single_session");
        properties.getConfigs().put("api", config);

        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());

        assertThatThrownBy(repository::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no steps");
    }

    private LaneStrategiesProperties baseProperties() {
        final LaneStrategiesProperties properties = new LaneStrategiesProperties();
        properties.setCommonInstructionRefs(List.of("shared/common-rules.md"));
        properties.setConfigs(new LinkedHashMap<>());
        return properties;
    }

    private Map<String, LaneStrategiesProperties.StrategyConfig> baseStrategyMap() {
        final Map<String, LaneStrategiesProperties.StrategyConfig> configs = new LinkedHashMap<>();
        configs.put("analyzer", strategy(step("scope_slicing", "Scope Slicing", null, List.of("additional-instructions/scope-context-usage.md", "lane-instructions/analyzer/scope-slicing.md"))));
        configs.put("architect", strategy(step("input_normalization", "Input Normalization", "TASKS", List.of("lane-instructions/architect/input-normalization.md"))));
        configs.put("api", strategy(step("preparation", "Preparation", null, List.of("additional-instructions/preparation-to-work.md"))));
        configs.put("qa_lead", strategy(step("qa_context", "QA Context", "TASKS", List.of("lane-instructions/qa_lead/qa-context.md"))));
        configs.put("implement_be", strategy(step("backend_context", "Backend Context", "TASKS", List.of("lane-instructions/implement_be/backend-context.md"))));
        configs.put("implement_fe", strategy(step("frontend_context", "Frontend Context", "TASKS", List.of("lane-instructions/implement_fe/frontend-context.md"))));
        configs.put("test_unit", strategy(step("unit_test_context", "Unit Test Context", "TASKS", List.of("lane-instructions/test_unit/unit-test-context.md"))));
        configs.put("test_it", strategy(step("it_test_context", "IT Test Context", "TASKS", List.of("lane-instructions/test_it/it-test-context.md"))));
        configs.put("test_ui", strategy(step("ui_test_context", "UI Test Context", "TASKS", List.of("lane-instructions/test_ui/ui-test-context.md"))));
        configs.put("reviewer", strategy(step("review_context", "Review Context", null, List.of("lane-instructions/reviewer/review-context.md"))));
        configs.put("event", strategy(step("contract_changes", "Contract Changes", "TASKS", List.of("additional-instructions/event-contract-rules.md"))));
        return configs;
    }

    private static LaneStrategiesProperties.StrategyConfig strategy(final LaneStrategiesProperties.StepConfig... steps) {
        final LaneStrategiesProperties.StrategyConfig config = new LaneStrategiesProperties.StrategyConfig();
        config.setVersion(1);
        config.setSessionMode("single_session");
        config.setSteps(List.of(steps));
        return config;
    }

    private static LaneStrategiesProperties.StepConfig step(final String id,
                                                            final String title,
                                                            final String taskPlaceholder,
                                                            final List<String> refs) {
        return step(id, title, taskPlaceholder, null, refs);
    }

    private static LaneStrategiesProperties.StepConfig step(final String id,
                                                            final String title,
                                                            final String taskPlaceholder,
                                                            final String completionContractPlaceholder,
                                                            final List<String> refs) {
        final LaneStrategiesProperties.StepConfig step = new LaneStrategiesProperties.StepConfig();
        step.setId(id);
        step.setTitle(title);
        step.setTaskPlaceholder(taskPlaceholder);
        step.setCompletionContractPlaceholder(completionContractPlaceholder);
        step.setInstructionRefs(refs);
        return step;
    }

    private static LaneStrategiesProperties.StepConfig stepWithValidator(final String id,
                                                                         final String title,
                                                                         final String validator,
                                                                         final List<String> refs) {
        final LaneStrategiesProperties.StepConfig step = step(id, title, null, refs);
        step.setValidator(validator);
        return step;
    }
}
