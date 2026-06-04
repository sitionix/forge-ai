package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import com.sitionix.forgeai.infrastructure.resources.lanestrategy.LaneStrategiesProperties;
import com.sitionix.forgeai.infrastructure.resources.lanestrategy.ResourceLaneStrategyRepository;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class LaneStrategiesConfigurationIT {

    private static final Map<Agent, List<String>> EXPECTED_STEP_IDS = Map.ofEntries(
            Map.entry(Agent.ANALYZER, List.of(
                    "scope_slicing",
                    "architect_handoff",
                    "qa_lead_handoff",
                    "completion"
            )),
            Map.entry(Agent.ARCHITECT, List.of(
                    "input_normalization",
                    "architecture_direction",
                    "api_decision",
                    "event_decision",
                    "implementation_handoff",
                    "completion"
            )),
            Map.entry(Agent.API, List.of(
                    "preparation",
                    "contract_changes",
                    "version_update",
                    "pr",
                    "generation",
                    "completion"
            )),
            Map.entry(Agent.EVENT, List.of(
                    "event_context",
                    "event_delivery",
                    "completion"
            )),
            Map.entry(Agent.QA_LEAD, List.of(
                    "qa_context",
                    "qa_focus",
                    "test_case_design",
                    "unit_test_notes",
                    "completion"
            )),
            Map.entry(Agent.IMPLEMENT_BE, List.of(
                    "backend_context",
                    "production_implementation",
                    "local_verification",
                    "completion"
            )),
            Map.entry(Agent.IMPLEMENT_FE, List.of(
                    "frontend_context",
                    "production_implementation",
                    "local_verification",
                    "completion"
            )),
            Map.entry(Agent.TEST_UNIT, List.of(
                    "unit_test_context",
                    "unit_test_usecase_service",
                    "unit_test_core",
                    "unit_test_mapper",
                    "unit_test_controller",
                    "unit_test_generated_artifacts",
                    "completion"
            )),
            Map.entry(Agent.TEST_IT, List.of(
                    "it_test_context",
                    "forge_it_setup",
                    "fixtures",
                    "case_implementation",
                    "http_mvc_flow",
                    "kafka_flow",
                    "postgresql_flow",
                    "wiremock_flow",
                    "completion"
            )),
            Map.entry(Agent.TEST_UI, List.of(
                    "ui_test_context",
                    "ui_test_implementation",
                    "local_verification",
                    "completion"
            )),
            Map.entry(Agent.REVIEWER, List.of(
                    "review_context",
                    "review_execution",
                    "completion"
            ))
    );

    private LaneStrategyRepository laneStrategyRepository;

    @BeforeEach
    void setUp() {
        this.laneStrategyRepository = laneStrategyRepository();
    }

    @Test
    @DisplayName("Should load expected ordered strategy steps for every executable lane")
    void givenRealLaneStrategiesYaml_whenLoaded_thenEveryAgentHasExpectedStepOrder() {
        for (final Agent agent : Agent.values()) {
            final LaneStrategy strategy = this.laneStrategyRepository.findByAgentId(agent.getId());
            final List<String> expectedStepIds = EXPECTED_STEP_IDS.get(agent);

            assertThat(expectedStepIds)
                    .as("Expected strategy contract missing in test for agent=%s", agent.getId())
                    .isNotNull();
            assertThat(strategy.getSteps())
                    .extracting(LaneStrategyStep::getId)
                    .containsExactlyElementsOf(expectedStepIds);
            assertThat(strategy.getSteps())
                    .extracting(LaneStrategyStep::getOrder)
                    .containsExactlyElementsOf(expectedOrders(expectedStepIds));
            assertThat(strategy.getSteps())
                    .allSatisfy(step -> assertThat(step.getInstructionRefs())
                            .as("instruction refs for agent=%s step=%s", agent.getId(), step.getId())
                            .isNotEmpty());
        }
    }

    private static List<Integer> expectedOrders(final List<String> expectedStepIds) {
        return java.util.stream.IntStream.rangeClosed(1, expectedStepIds.size())
                .boxed()
                .toList();
    }

    private static LaneStrategyRepository laneStrategyRepository() {
        final LaneStrategiesProperties properties = laneStrategiesProperties();
        final ResourceLaneStrategyRepository repository = new ResourceLaneStrategyRepository(properties, new DefaultResourceLoader());
        repository.init();
        return repository;
    }

    private static LaneStrategiesProperties laneStrategiesProperties() {
        final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("lane-strategies.yml"));
        final Properties properties = yaml.getObject();
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("lane-strategies", properties == null ? new Properties() : properties));
        return Binder.get(environment)
                .bind("forge.ai.lane-strategies", LaneStrategiesProperties.class)
                .orElseThrow(() -> new IllegalStateException("Failed to bind lane-strategies.yml"));
    }
}
