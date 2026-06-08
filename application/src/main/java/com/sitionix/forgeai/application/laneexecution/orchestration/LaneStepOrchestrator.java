package com.sitionix.forgeai.application.laneexecution.orchestration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LaneStepOrchestrator {

    String value();

    Class<?> input() default LaneStepOrchestratorInput.class;
}
