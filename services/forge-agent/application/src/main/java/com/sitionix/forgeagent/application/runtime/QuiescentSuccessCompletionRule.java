package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
public class QuiescentSuccessCompletionRule implements WorkflowCompletionRule {

    @Override
    public boolean supports(final WorkflowCompletionContext context) {
        return true;
    }

    @Override
    public WorkflowCompletionDecision decision(final WorkflowCompletionContext context) {
        return new SuccessfulWorkflowDecision();
    }
}
