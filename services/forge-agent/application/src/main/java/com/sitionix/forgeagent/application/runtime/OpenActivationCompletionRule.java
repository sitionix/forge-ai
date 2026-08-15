package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class OpenActivationCompletionRule implements WorkflowCompletionRule {

    @Override
    public boolean supports(final WorkflowCompletionContext context) {
        return context.hasOpenActivation();
    }

    @Override
    public WorkflowCompletionDecision decision(final WorkflowCompletionContext context) {
        return new RunningWorkflowDecision();
    }
}
