package com.sitionix.forgeai.application.laneexecution.validation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

@Component
public class LaneStepEvidenceValidatorRegistry {

    private final ObjectMapper objectMapper;
    private final Map<String, LaneStepEvidenceValidator<?>> validators;

    public LaneStepEvidenceValidatorRegistry(final ObjectMapper objectMapper,
                                             final List<LaneStepEvidenceValidator<?>> validators) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.validators = this.index(validators);
    }

    public void validate(final LaneStepValidationContext context, final Map<String, Object> evidence) {
        final LaneStrategyStep step = context.step();
        if (!this.hasText(step.getValidator())) {
            return;
        }
        final LaneStepEvidenceValidator<?> validator = this.validators.get(step.getValidator());
        if (validator == null) {
            throw new IllegalArgumentException("Lane step validator not found: stepId="
                    + step.getId() + ", validator=" + step.getValidator());
        }
        if (evidence == null) {
            throw new IllegalArgumentException("Lane step evidence is required: stepId=" + step.getId());
        }
        this.validateConverted(context, evidence, validator, this.metadata(validator));
    }

    private <T> void validateConverted(final LaneStepValidationContext context,
                                       final Map<String, Object> evidence,
                                       final LaneStepEvidenceValidator<T> validator,
                                       final LaneStepValidator metadata) {
        final Object typedEvidence;
        try {
            typedEvidence = this.objectMapper.convertValue(evidence, metadata.evidence());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("Lane step evidence does not match type "
                    + metadata.evidence().getSimpleName() + ": " + ex.getMessage(), ex);
        }
        validator.validate(context, this.castEvidence(typedEvidence));
    }

    @SuppressWarnings("unchecked")
    private <T> T castEvidence(final Object evidence) {
        return (T) evidence;
    }

    private Map<String, LaneStepEvidenceValidator<?>> index(final List<LaneStepEvidenceValidator<?>> validators) {
        final Map<String, LaneStepEvidenceValidator<?>> indexed = new LinkedHashMap<>();
        validators.forEach(validator -> {
            final LaneStepValidator metadata = this.metadata(validator);
            final LaneStepEvidenceValidator<?> previous = indexed.putIfAbsent(metadata.value(), validator);
            if (previous != null) {
                throw new IllegalStateException("Duplicate lane step evidence validator: " + metadata.value());
            }
        });
        return Map.copyOf(indexed);
    }

    private LaneStepValidator metadata(final LaneStepEvidenceValidator<?> validator) {
        final Class<?> validatorClass = AopUtils.getTargetClass(validator);
        final LaneStepValidator metadata = AnnotatedElementUtils.findMergedAnnotation(validatorClass, LaneStepValidator.class);
        if (metadata == null) {
            throw new IllegalStateException("Lane step evidence validator is missing @LaneStepValidator: "
                    + validatorClass.getName());
        }
        if (!this.hasText(metadata.value())) {
            throw new IllegalStateException("Lane step evidence validator has empty name: " + validatorClass.getName());
        }
        return metadata;
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
