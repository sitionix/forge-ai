package com.sitionix.forgeai.application.laneexecution.orchestration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

@Component
public class LaneStepOrchestratorRegistry {

    private final ObjectMapper objectMapper;
    private final Map<String, LaneStepOrchestratorHandler<?>> handlers;

    public LaneStepOrchestratorRegistry(final ObjectMapper objectMapper,
                                        final List<LaneStepOrchestratorHandler<?>> handlers) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.handlers = this.index(handlers);
    }

    public LaneStepDoneResult execute(final LaneStepOrchestratorContext context,
                                      final Map<String, Object> input) {
        final LaneStrategyStep step = context.step();
        if (!step.isOrchestratorStep()) {
            throw new IllegalArgumentException("Lane step is not orchestrator step: stepId=" + step.getId());
        }
        final LaneStepOrchestratorHandler<?> handler = this.handlers.get(step.getHandler());
        if (handler == null) {
            throw new IllegalArgumentException("Lane step orchestrator handler not found: stepId="
                    + step.getId() + ", handler=" + step.getHandler());
        }
        final LaneStepDoneResult result = this.executeConverted(context, input, handler, this.metadata(handler));
        if (result == null) {
            throw new IllegalArgumentException("Lane step orchestrator handler returned null: stepId=" + step.getId());
        }
        if (!step.getId().equals(result.getStepId())) {
            throw new IllegalArgumentException("Lane step orchestrator handler returned invalid stepId: stepId="
                    + result.getStepId() + ", expected=" + step.getId());
        }
        return result;
    }

    private <T> LaneStepDoneResult executeConverted(final LaneStepOrchestratorContext context,
                                                   final Map<String, Object> input,
                                                   final LaneStepOrchestratorHandler<T> handler,
                                                   final LaneStepOrchestrator metadata) {
        final Object typedInput;
        try {
            typedInput = this.objectMapper.convertValue(input, metadata.input());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("Lane step orchestrator input does not match type "
                    + metadata.input().getSimpleName() + ": " + ex.getMessage(), ex);
        }
        return handler.execute(context, this.castInput(typedInput));
    }

    @SuppressWarnings("unchecked")
    private <T> T castInput(final Object input) {
        return (T) input;
    }

    private Map<String, LaneStepOrchestratorHandler<?>> index(final List<LaneStepOrchestratorHandler<?>> handlers) {
        final Map<String, LaneStepOrchestratorHandler<?>> indexed = new LinkedHashMap<>();
        handlers.forEach(handler -> {
            final LaneStepOrchestrator metadata = this.metadata(handler);
            final LaneStepOrchestratorHandler<?> previous = indexed.putIfAbsent(metadata.value(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate lane step orchestrator handler: " + metadata.value());
            }
        });
        return Map.copyOf(indexed);
    }

    private LaneStepOrchestrator metadata(final LaneStepOrchestratorHandler<?> handler) {
        final Class<?> handlerClass = AopUtils.getTargetClass(handler);
        final LaneStepOrchestrator metadata = AnnotatedElementUtils.findMergedAnnotation(handlerClass, LaneStepOrchestrator.class);
        if (metadata == null) {
            throw new IllegalStateException("Lane step orchestrator handler is missing @LaneStepOrchestrator: "
                    + handlerClass.getName());
        }
        if (metadata.value() == null || metadata.value().isBlank()) {
            throw new IllegalStateException("Lane step orchestrator handler has empty name: " + handlerClass.getName());
        }
        return metadata;
    }
}
