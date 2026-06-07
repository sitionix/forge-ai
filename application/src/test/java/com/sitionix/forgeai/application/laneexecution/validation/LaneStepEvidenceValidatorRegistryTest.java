package com.sitionix.forgeai.application.laneexecution.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneStepEvidenceValidatorRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void givenStepWithoutValidator_whenValidate_thenSkip() {
        final LaneStepEvidenceValidatorRegistry registry = new LaneStepEvidenceValidatorRegistry(this.objectMapper, List.of());

        registry.validate(this.context(this.step(null)), Map.of("extra", "allowed"));
    }

    @Test
    void givenConfiguredValidator_whenValidate_thenConvertEvidenceAndInvokeBean() {
        final AtomicReference<TestEvidence> seen = new AtomicReference<>();
        final LaneStepEvidenceValidatorRegistry registry = new LaneStepEvidenceValidatorRegistry(
                this.objectMapper,
                List.of(new TestValidator(seen))
        );

        registry.validate(this.context(this.step("testValidator")), Map.of("value", "ok"));

        assertThat(seen.get()).isEqualTo(new TestEvidence("ok"));
    }

    @Test
    void givenUnknownEvidenceField_whenValidate_thenReject() {
        final LaneStepEvidenceValidatorRegistry registry = new LaneStepEvidenceValidatorRegistry(
                this.objectMapper,
                List.of(new TestValidator(new AtomicReference<>()))
        );

        assertThatThrownBy(() -> registry.validate(
                this.context(this.step("testValidator")),
                Map.of("value", "ok", "unexpected", "no")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match type");
    }

    @Test
    void givenValidatorWithoutAnnotation_whenConstruct_thenReject() {
        assertThatThrownBy(() -> new LaneStepEvidenceValidatorRegistry(
                this.objectMapper,
                List.of(new MissingAnnotationValidator())
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing @LaneStepValidator");
    }

    @Test
    void givenDuplicateValidatorNames_whenConstruct_thenReject() {
        assertThatThrownBy(() -> new LaneStepEvidenceValidatorRegistry(
                this.objectMapper,
                List.of(new TestValidator(new AtomicReference<>()), new DuplicateNameValidator())
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate lane step evidence validator");
    }

    private LaneStepValidationContext context(final LaneStrategyStep step) {
        final LaneStrategy strategy = LaneStrategy.builder()
                .agentId("analyzer")
                .version(1)
                .sessionMode("single_session")
                .steps(List.of(step))
                .build();
        return new LaneStepValidationContext(
                ReadyToStartLane.builder()
                        .ticketId(UUID.randomUUID())
                        .ticketKey("SITIONIX-1")
                        .laneId(UUID.randomUUID())
                        .agent(Agent.ANALYZER)
                        .scope("automationservice-sox")
                        .serviceId("atmssox")
                        .attempt(1)
                        .build(),
                strategy,
                step,
                new CodexLaneWorkspace(System.getProperty("user.dir"), List.of(System.getProperty("user.dir"))),
                UUID.randomUUID(),
                "session"
        );
    }

    private LaneStrategyStep step(final String validator) {
        return LaneStrategyStep.builder()
                .id("example")
                .title("Example")
                .order(1)
                .validator(validator)
                .instructionRefs(List.of())
                .build();
    }

    private record TestEvidence(String value) {
    }

    @LaneStepValidator(value = "testValidator", evidence = TestEvidence.class)
    private static final class TestValidator implements LaneStepEvidenceValidator<TestEvidence> {

        private final AtomicReference<TestEvidence> seen;

        private TestValidator(final AtomicReference<TestEvidence> seen) {
            this.seen = seen;
        }

        @Override
        public void validate(final LaneStepValidationContext context, final TestEvidence evidence) {
            this.seen.set(evidence);
        }
    }

    @LaneStepValidator(value = "testValidator", evidence = TestEvidence.class)
    private static final class DuplicateNameValidator implements LaneStepEvidenceValidator<TestEvidence> {

        @Override
        public void validate(final LaneStepValidationContext context, final TestEvidence evidence) {
        }
    }

    private static final class MissingAnnotationValidator implements LaneStepEvidenceValidator<TestEvidence> {

        @Override
        public void validate(final LaneStepValidationContext context, final TestEvidence evidence) {
        }
    }
}
