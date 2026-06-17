package com.sitionix.forgeai.application.laneexecution.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStepType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneStepOrchestratorRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void givenAnnotatedHandlerAndTypedSubsetInput_whenExecute_thenConvertInputAndReturnResult() {
        final RecordingHandler handler = new RecordingHandler();
        final LaneStepOrchestratorRegistry registry = new LaneStepOrchestratorRegistry(this.objectMapper, List.of(handler));

        final LaneStepDoneResult result = registry.execute(this.context("collect_artifacts", "collectArtifacts"), Map.of(
                "ticketId", "11111111-1111-1111-1111-111111111111",
                "stepId", "collect_artifacts",
                "previousEvidence", Map.of("branch", "feature/SITIONIX-1"),
                "stepEvidence", Map.of("preparation", Map.of("valid", true))
        ));

        assertThat(handler.input).isNotNull();
        assertThat(handler.input.stepId()).isEqualTo("collect_artifacts");
        assertThat(handler.input.previousEvidence()).containsEntry("branch", "feature/SITIONIX-1");
        assertThat(result.getStepId()).isEqualTo("collect_artifacts");
        assertThat(result.getEvidence()).containsEntry("handled", true);
    }

    @Test
    void givenDuplicateHandlerName_whenCreateRegistry_thenReject() {
        assertThatThrownBy(() -> new LaneStepOrchestratorRegistry(this.objectMapper, List.of(
                new RecordingHandler(),
                new DuplicateRecordingHandler()
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate lane step orchestrator handler");
    }

    @Test
    void givenHandlerWithoutAnnotation_whenCreateRegistry_thenReject() {
        assertThatThrownBy(() -> new LaneStepOrchestratorRegistry(this.objectMapper, List.of(new MissingAnnotationHandler())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing @LaneStepOrchestrator");
    }

    @Test
    void givenUnknownHandler_whenExecute_thenReject() {
        final LaneStepOrchestratorRegistry registry = new LaneStepOrchestratorRegistry(this.objectMapper, List.of(new RecordingHandler()));

        assertThatThrownBy(() -> registry.execute(this.context("collect_artifacts", "missing"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handler not found");
    }

    @Test
    void givenNonOrchestratorStep_whenExecute_thenReject() {
        final LaneStepOrchestratorRegistry registry = new LaneStepOrchestratorRegistry(this.objectMapper, List.of(new RecordingHandler()));
        final LaneStepOrchestratorContext context = new LaneStepOrchestratorContext(
                null,
                null,
                null,
                LaneStrategyStep.builder()
                        .id("collect_artifacts")
                        .type(LaneStrategyStepType.AGENT)
                        .handler("collectArtifacts")
                        .build(),
                null,
                null,
                null,
                1
        );

        assertThatThrownBy(() -> registry.execute(context, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not orchestrator step");
    }

    @Test
    void givenHandlerReturnsWrongStepId_whenExecute_thenReject() {
        final LaneStepOrchestratorRegistry registry = new LaneStepOrchestratorRegistry(this.objectMapper, List.of(new WrongStepHandler()));

        assertThatThrownBy(() -> registry.execute(this.context("collect_artifacts", "wrongStep"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid stepId");
    }

    @Test
    void givenHandlerReturnsNull_whenExecute_thenReject() {
        final LaneStepOrchestratorRegistry registry = new LaneStepOrchestratorRegistry(this.objectMapper, List.of(new NullResultHandler()));

        assertThatThrownBy(() -> registry.execute(this.context("collect_artifacts", "nullResult"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returned null");
    }

    private LaneStepOrchestratorContext context(final String stepId, final String handler) {
        return new LaneStepOrchestratorContext(
                null,
                null,
                null,
                LaneStrategyStep.builder()
                        .id(stepId)
                        .type(LaneStrategyStepType.ORCHESTRATOR)
                        .handler(handler)
                        .build(),
                null,
                null,
                null,
                1
        );
    }

    private record CollectorInput(
            String stepId,
            Map<String, Object> previousEvidence,
            Map<String, Map<String, Object>> stepEvidence
    ) {
    }

    @LaneStepOrchestrator(value = "collectArtifacts", input = CollectorInput.class)
    private static class RecordingHandler implements LaneStepOrchestratorHandler<CollectorInput> {

        private CollectorInput input;

        @Override
        public LaneStepDoneResult execute(final LaneStepOrchestratorContext context, final CollectorInput input) {
            this.input = input;
            return LaneStepDoneResult.builder()
                    .stepId(context.step().getId())
                    .summary("done")
                    .evidence(Map.of("handled", true))
                    .build();
        }
    }

    @LaneStepOrchestrator(value = "collectArtifacts", input = CollectorInput.class)
    private static final class DuplicateRecordingHandler extends RecordingHandler {
    }

    private static final class MissingAnnotationHandler implements LaneStepOrchestratorHandler<CollectorInput> {

        @Override
        public LaneStepDoneResult execute(final LaneStepOrchestratorContext context, final CollectorInput input) {
            return null;
        }
    }

    @LaneStepOrchestrator(value = "wrongStep", input = CollectorInput.class)
    private static final class WrongStepHandler implements LaneStepOrchestratorHandler<CollectorInput> {

        @Override
        public LaneStepDoneResult execute(final LaneStepOrchestratorContext context, final CollectorInput input) {
            return LaneStepDoneResult.builder()
                    .stepId("other")
                    .summary("done")
                    .evidence(Map.of())
                    .build();
        }
    }

    @LaneStepOrchestrator(value = "nullResult", input = CollectorInput.class)
    private static final class NullResultHandler implements LaneStepOrchestratorHandler<CollectorInput> {

        @Override
        public LaneStepDoneResult execute(final LaneStepOrchestratorContext context, final CollectorInput input) {
            return null;
        }
    }
}
