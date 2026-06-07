package com.sitionix.forgeai.application.laneexecution.validation;

public interface LaneStepEvidenceValidator<T> {

    void validate(LaneStepValidationContext context, T evidence);
}
